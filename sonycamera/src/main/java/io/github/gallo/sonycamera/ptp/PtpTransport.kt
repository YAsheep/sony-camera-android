package io.github.gallo.sonycamera.ptp

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Low-level PTP protocol transport over USB bulk transfers.
 *
 * Handles sending PTP command containers and receiving response/data containers.
 * All operations are synchronous and should be called from a background thread.
 *
 * PTP container format:
 * - Bytes 0-3: Container length (little-endian uint32)
 * - Bytes 4-5: Container type (command=1, data=2, response=3, event=4)
 * - Bytes 6-7: Operation/Response code (little-endian uint16)
 * - Bytes 8-11: Transaction ID (little-endian uint32)
 * - Bytes 12+: Parameters or data payload
 */
class PtpTransport(
    private val connection: UsbDeviceConnection,
    private val bulkOut: UsbEndpoint,   // Host → Device
    private val bulkIn: UsbEndpoint,    // Device → Host
    private val interruptIn: UsbEndpoint? = null // Events (optional)
) {
    companion object {
        private const val TAG = "PtpTransport"
        // Keep a single Android USB request comfortably below OEM usbfs/URB
        // limits. Large PTP payloads are assembled from multiple chunks.
        private const val USB_READ_CHUNK_SIZE = 16 * 1024
        private const val MAX_PTP_CONTAINER_SIZE = 128 * 1024 * 1024
    }

    // Serializes all bulk/control transfers on this endpoint set.
    // A single PTP operation is a sequence of transfers (command → data → response)
    // and must be atomic relative to any other caller, or we desync the pipe.
    // ReentrantLock — not Mutex — because callers are blocking and we want a
    // public method to be safe to call from within another public method.
    // Fair ordering prevents the continuous event pump and liveview polling
    // from starving one another. Android's UsbDeviceConnection cannot safely
    // run two transfers concurrently on every OEM implementation.
    private val lock = ReentrantLock(true)

    private var transactionId = 0
    // A USB bulk read is not guaranteed to end on a PTP container boundary.
    // In particular, when a data container ends on a 512-byte USB packet
    // boundary, Android may return the following response container in the
    // same bulkTransfer. Preserve those bytes for the next container parser.
    private var pendingBulkIn = ByteArray(0)

    /**
     * Recovery-only PTP DeviceReset sequence.
     *
     * A fresh PTP connection must start directly with OpenSession. In
     * particular, do not send a zero-length CancelRequest here: the Still
     * Image USB class defines a six-byte cancel payload, so such a transfer
     * does not cancel a transaction. This method is deliberately not part of
     * normal initialization and is retained only for an explicit recovery
     * path after a confirmed transport failure.
     */
    fun resetDevice() = lock.withLock {
        pendingBulkIn = ByteArray(0)
        // PTP Device Reset (class request 0x66).
        val resetResult = connection.controlTransfer(
            0x21, 0x66, 0, 0, null, 0, 5000
        )
        Log.d(TAG, "PTP device reset result: $resetResult")
        Thread.sleep(100)

        // Clear HALT on bulk endpoints.
        val clearOut = connection.controlTransfer(
            0x02, 0x01, 0, bulkOut.address, null, 0, 2000
        )
        Log.d(TAG, "Clear HALT on bulkOut (addr=${bulkOut.address}): $clearOut")

        val clearIn = connection.controlTransfer(
            0x02, 0x01, 0, bulkIn.address, null, 0, 2000
        )
        Log.d(TAG, "Clear HALT on bulkIn (addr=${bulkIn.address}): $clearIn")

        // Drain stale data from bulk IN (leftovers from MTP service
        // probing, or a previously-interrupted transfer).
        val drainBuf = ByteArray(512)
        var drained = 0
        while (true) {
            val read = connection.bulkTransfer(bulkIn, drainBuf, drainBuf.size, 200)
            if (read <= 0) break
            drained += read
        }
        if (drained > 0) Log.d(TAG, "Drained $drained stale bytes from bulk IN")

        // Short settle so the camera's PTP state machine is ready
        // to accept OpenSession.
        Thread.sleep(500)
    }

    /**
     * Send a PTP command and receive the response.
     *
     * @param operationCode PTP operation code
     * @param params Up to 5 uint32 parameters
     * @return PtpResponse with response code and parameters
     */
    fun sendCommand(operationCode: Int, vararg params: Int): PtpResponse =
        sendCommand(operationCode, responseTimeoutMs = PtpConstants.USB_TIMEOUT_MS, params = params)

    /**
     * Send a PTP command with a custom response-read timeout. Useful during
     * the initial handshake where a non-responding camera should fail fast
     * (~1.5s) so we can escalate to a heavy USB reset, rather than the 5s
     * default which dominates recovery latency.
     */
    fun sendCommand(
        operationCode: Int,
        responseTimeoutMs: Int,
        vararg params: Int
    ): PtpResponse = lock.withLock {
        val txId = nextTransactionId()

        val paramBytes = params.size * 4
        val containerLength = PtpConstants.HEADER_SIZE + paramBytes
        val buffer = ByteBuffer.allocate(containerLength).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(containerLength)
        buffer.putShort(PtpConstants.CONTAINER_TYPE_COMMAND.toShort())
        buffer.putShort(operationCode.toShort())
        buffer.putInt(txId)
        for (param in params) {
            buffer.putInt(param)
        }

        val sent = connection.bulkTransfer(bulkOut, buffer.array(), containerLength, 10000)
        if (sent != containerLength) {
            Log.e(TAG, "Failed to send command 0x${operationCode.toString(16)}: " +
                    "bulk OUT wrote $sent/$containerLength bytes")
            return@withLock PtpResponse(
                PtpConstants.RESP_GENERAL_ERROR,
                txId,
                commandDelivered = false
            )
        }

        readResponse(txId, responseTimeoutMs)
    }

    /**
     * Send a PTP command that returns data (e.g., GetDeviceInfo, GetObject).
     *
     * @param operationCode PTP operation code
     * @param params Up to 5 uint32 parameters
     * @return PtpDataResponse with response code and data bytes
     */
    fun sendCommandWithData(operationCode: Int, vararg params: Int): PtpDataResponse = lock.withLock {
        val txId = nextTransactionId()

        // Build and send command container
        val paramBytes = params.size * 4
        val containerLength = PtpConstants.HEADER_SIZE + paramBytes
        val buffer = ByteBuffer.allocate(containerLength).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(containerLength)
        buffer.putShort(PtpConstants.CONTAINER_TYPE_COMMAND.toShort())
        buffer.putShort(operationCode.toShort())
        buffer.putInt(txId)
        for (param in params) {
            buffer.putInt(param)
        }

        val sent = connection.bulkTransfer(
            bulkOut, buffer.array(), containerLength, PtpConstants.USB_TIMEOUT_MS
        )
        if (sent != containerLength) {
            Log.e(TAG, "DataIn cmd 0x${operationCode.toString(16)} send failed: " +
                    "bulk OUT wrote $sent/$containerLength bytes")
            return@withLock PtpDataResponse(PtpConstants.RESP_GENERAL_ERROR, txId, ByteArray(0))
        }

        // Read data phase + response
        readDataAndResponse(txId)
    }

    /**
     * Send a PTP command that returns data, with a custom timeout for the first read.
     * Useful for probing handles that might not have data (avoids 10s default timeout).
     */
    fun sendCommandWithDataShortTimeout(operationCode: Int, timeoutMs: Int, vararg params: Int): PtpDataResponse = lock.withLock {
        val txId = nextTransactionId()

        val paramBytes = params.size * 4
        val containerLength = PtpConstants.HEADER_SIZE + paramBytes
        val buffer = ByteBuffer.allocate(containerLength).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(containerLength)
        buffer.putShort(PtpConstants.CONTAINER_TYPE_COMMAND.toShort())
        buffer.putShort(operationCode.toShort())
        buffer.putInt(txId)
        for (param in params) {
            buffer.putInt(param)
        }

        val sent = connection.bulkTransfer(bulkOut, buffer.array(), containerLength, timeoutMs)
        if (sent != containerLength) {
            Log.e(TAG, "DataIn cmd 0x${operationCode.toString(16)} send failed: " +
                    "bulk OUT wrote $sent/$containerLength bytes")
            return@withLock PtpDataResponse(PtpConstants.RESP_GENERAL_ERROR, txId, ByteArray(0))
        }

        readDataAndResponse(
            expectedTxId = txId,
            firstContainerTimeoutMs = timeoutMs
        )
    }

    /**
     * Send a PTP command with a data payload to the device (e.g., SetDevicePropValue).
     *
     * @param operationCode PTP operation code
     * @param data The data payload to send
     * @param params Up to 5 uint32 parameters
     * @return PtpResponse with response code
     */
    fun sendCommandWithDataOut(operationCode: Int, data: ByteArray, vararg params: Int): PtpResponse = lock.withLock {
        val txId = nextTransactionId()

        // Send command container
        val paramBytes = params.size * 4
        val cmdLength = PtpConstants.HEADER_SIZE + paramBytes
        val cmdBuffer = ByteBuffer.allocate(cmdLength).order(ByteOrder.LITTLE_ENDIAN)
        cmdBuffer.putInt(cmdLength)
        cmdBuffer.putShort(PtpConstants.CONTAINER_TYPE_COMMAND.toShort())
        cmdBuffer.putShort(operationCode.toShort())
        cmdBuffer.putInt(txId)
        for (param in params) {
            cmdBuffer.putInt(param)
        }

        var sent = connection.bulkTransfer(
            bulkOut, cmdBuffer.array(), cmdLength, PtpConstants.USB_TIMEOUT_MS
        )
        if (sent != cmdLength) {
            Log.e(TAG, "DataOut cmd 0x${operationCode.toString(16)} send failed: " +
                    "bulk OUT wrote $sent/$cmdLength bytes")
            return@withLock PtpResponse(
                PtpConstants.RESP_GENERAL_ERROR,
                txId,
                commandDelivered = false
            )
        }

        // Send data container
        val dataLength = PtpConstants.HEADER_SIZE + data.size
        val dataBuffer = ByteBuffer.allocate(dataLength).order(ByteOrder.LITTLE_ENDIAN)
        dataBuffer.putInt(dataLength)
        dataBuffer.putShort(PtpConstants.CONTAINER_TYPE_DATA.toShort())
        dataBuffer.putShort(operationCode.toShort())
        dataBuffer.putInt(txId)
        dataBuffer.put(data)

        sent = connection.bulkTransfer(
            bulkOut, dataBuffer.array(), dataLength, PtpConstants.USB_TIMEOUT_MS
        )
        if (sent != dataLength) {
            Log.e(TAG, "DataOut 0x${operationCode.toString(16)} data phase failed: " +
                    "bulk OUT wrote $sent/$dataLength bytes")
            return@withLock PtpResponse(
                PtpConstants.RESP_GENERAL_ERROR,
                txId,
                commandDelivered = false
            )
        }

        readResponseQuick(txId)
    }

    /**
     * Send a PTP command with a data-out phase, then receive a data-in phase and response.
     *
     * Used by Sony's proprietary image retrieval where the host sends an image type
     * payload (photo vs liveview) and the camera responds with the image data.
     *
     * Flow: Command → Data-out → Data-in → Response
     */
    fun sendCommandWithDataOutAndDataIn(
        operationCode: Int,
        dataOut: ByteArray,
        timeoutMs: Int = PtpConstants.USB_TIMEOUT_MS,
        vararg params: Int
    ): PtpDataResponse = lock.withLock {
        val txId = nextTransactionId()

        // 1. Send command container
        val paramBytes = params.size * 4
        val cmdLength = PtpConstants.HEADER_SIZE + paramBytes
        val cmdBuffer = ByteBuffer.allocate(cmdLength).order(ByteOrder.LITTLE_ENDIAN)
        cmdBuffer.putInt(cmdLength)
        cmdBuffer.putShort(PtpConstants.CONTAINER_TYPE_COMMAND.toShort())
        cmdBuffer.putShort(operationCode.toShort())
        cmdBuffer.putInt(txId)
        for (param in params) {
            cmdBuffer.putInt(param)
        }

        var sent = connection.bulkTransfer(bulkOut, cmdBuffer.array(), cmdLength, timeoutMs)
        if (sent != cmdLength) {
            Log.e(TAG, "DataOutIn cmd 0x${operationCode.toString(16)} send failed: " +
                    "bulk OUT wrote $sent/$cmdLength bytes")
            return@withLock PtpDataResponse(PtpConstants.RESP_GENERAL_ERROR, txId, ByteArray(0))
        }

        // 2. Send data-out container
        val dataOutLength = PtpConstants.HEADER_SIZE + dataOut.size
        val dataOutBuffer = ByteBuffer.allocate(dataOutLength).order(ByteOrder.LITTLE_ENDIAN)
        dataOutBuffer.putInt(dataOutLength)
        dataOutBuffer.putShort(PtpConstants.CONTAINER_TYPE_DATA.toShort())
        dataOutBuffer.putShort(operationCode.toShort())
        dataOutBuffer.putInt(txId)
        dataOutBuffer.put(dataOut)

        sent = connection.bulkTransfer(
            bulkOut, dataOutBuffer.array(), dataOutLength, timeoutMs
        )
        if (sent != dataOutLength) {
            Log.e(TAG, "DataOutIn 0x${operationCode.toString(16)} data-out phase failed: " +
                    "bulk OUT wrote $sent/$dataOutLength bytes")
            return@withLock PtpDataResponse(PtpConstants.RESP_GENERAL_ERROR, txId, ByteArray(0))
        }

        // 3. Read data-in phase + response
        readDataAndResponse(
            expectedTxId = txId,
            firstContainerTimeoutMs = timeoutMs,
            responseTimeoutMs = timeoutMs
        )
    }

    /**
     * Read a PTP response with a short timeout (500ms).
     * Sony SetControlDeviceB commands may not send a response at all — the camera
     * accepts the command and executes it but stalls the IN endpoint.
     * Using a short timeout prevents blocking for 5+ seconds per command.
     */
    private fun readResponseQuick(expectedTxId: Int): PtpResponse {
        repeat(4) {
            val container = readNextBulkContainer(500)
                // No response within 500ms — camera may have accepted command silently.
                ?: return PtpResponse(PtpConstants.RESP_GENERAL_ERROR, expectedTxId)

            if (container.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                return container.toPtpResponse()
            }
            if (container.type != PtpConstants.CONTAINER_TYPE_DATA) {
                Log.w(TAG, "Expected quick response container, got type ${container.type}")
                return PtpResponse(PtpConstants.RESP_GENERAL_ERROR, expectedTxId)
            }
            // A stray data container is now already consumed exactly; continue
            // with any response bytes retained in pendingBulkIn.
        }
        return PtpResponse(PtpConstants.RESP_GENERAL_ERROR, expectedTxId)
    }

    /**
     * Read a PTP response container from the device.
     */
    private fun readResponse(
        expectedTxId: Int,
        timeoutMs: Int = PtpConstants.USB_TIMEOUT_MS
    ): PtpResponse {
        repeat(4) {
            val container = readNextBulkContainer(timeoutMs)
            if (container == null) {
                Log.e(TAG, "No complete PTP response container")
                return PtpResponse(PtpConstants.RESP_GENERAL_ERROR, expectedTxId)
            }
            if (container.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                return container.toPtpResponse()
            }

            Log.w(TAG, "Expected response container, got type ${container.type}")
            if (container.type != PtpConstants.CONTAINER_TYPE_DATA) {
                return PtpResponse(PtpConstants.RESP_GENERAL_ERROR, expectedTxId)
            }
            // The complete unexpected data container has already been consumed.
        }
        return PtpResponse(PtpConstants.RESP_GENERAL_ERROR, expectedTxId)
    }

    /**
     * Read data phase followed by response.
     */
    private fun readDataAndResponse(
        expectedTxId: Int,
        firstContainerTimeoutMs: Int = PtpConstants.USB_TIMEOUT_MS * 2,
        responseTimeoutMs: Int = PtpConstants.USB_TIMEOUT_MS
    ): PtpDataResponse {
        val container = readNextBulkContainer(firstContainerTimeoutMs)
        if (container == null) {
            Log.e(TAG, "No complete PTP data container")
            return PtpDataResponse(PtpConstants.RESP_GENERAL_ERROR, expectedTxId, ByteArray(0))
        }

        if (container.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
            // No data phase — just a response (e.g., error)
            return PtpDataResponse(container.code, container.transactionId, ByteArray(0))
        }

        if (container.type != PtpConstants.CONTAINER_TYPE_DATA) {
            Log.w(TAG, "Expected data container, got type ${container.type}")
            return PtpDataResponse(PtpConstants.RESP_GENERAL_ERROR, expectedTxId, ByteArray(0))
        }

        // readNextBulkContainer keeps any coalesced response bytes. This read
        // therefore succeeds immediately when Data and Response arrived in
        // the same Android bulkTransfer.
        val response = readResponse(expectedTxId, responseTimeoutMs)
        return PtpDataResponse(
            response.responseCode,
            container.transactionId,
            container.payload
        )
    }

    /**
     * Read exactly one PTP container from the Bulk IN byte stream.
     *
     * USB transfer boundaries and PTP container boundaries are independent.
     * This parser accepts split headers and preserves bytes beyond the current
     * container, preventing a Data read from swallowing its Response.
     */
    private fun readNextBulkContainer(timeoutMs: Int): BulkContainer? {
        val output = ByteArrayOutputStream(USB_READ_CHUNK_SIZE)
        var containerLength = -1

        while (containerLength < 0 || output.size() < containerLength) {
            val chunk = if (pendingBulkIn.isNotEmpty()) {
                pendingBulkIn.also { pendingBulkIn = ByteArray(0) }
            } else {
                val maxPacketSize = bulkIn.maxPacketSize.coerceAtLeast(1)
                val requestedSize = if (containerLength < 0) {
                    // One complete USB packet is enough to parse the PTP
                    // header, without risking a container-ending ZLP being
                    // left for the following response read.
                    maxPacketSize
                } else {
                    val remaining = containerLength - output.size()
                    if (remaining <= USB_READ_CHUNK_SIZE) {
                        // Leave room for one terminator packet. If the data
                        // length is a multiple of maxPacketSize, the camera's
                        // ZLP is consumed by this same URB. If the response is
                        // coalesced instead, the carry buffer preserves it.
                        remaining + maxPacketSize
                    } else {
                        USB_READ_CHUNK_SIZE
                    }
                }
                val buffer = ByteArray(requestedSize)
                val read = connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMs)
                if (read < 0) {
                    if (output.size() > 0) {
                        Log.e(TAG, "Incomplete PTP container: ${output.size()} bytes, USB read=$read")
                    }
                    return null
                }
                if (read == 0) {
                    // Honor can expose a terminator/driver edge case as a
                    // standalone 0-byte result. Never issue an immediate
                    // second blocking read on this OEM path: recover the
                    // endpoint, discard the affected transaction, and let the
                    // next liveview poll continue on a clean pipe.
                    val clearIn = connection.controlTransfer(
                        0x02, 0x01, 0, bulkIn.address, null, 0, 500
                    )
                    val drainBuffer = ByteArray(maxPacketSize)
                    val drained = if (clearIn >= 0) {
                        connection.bulkTransfer(bulkIn, drainBuffer, drainBuffer.size, 100)
                    } else {
                        -1
                    }
                    Log.w(TAG, "Bulk IN returned zero bytes; recovered endpoint " +
                            "(clear=$clearIn, drained=$drained)")
                    pendingBulkIn = ByteArray(0)
                    return null
                }
                buffer.copyOf(read)
            }

            output.write(chunk)

            if (containerLength < 0 && output.size() >= 4) {
                val bytes = output.toByteArray()
                containerLength = ByteBuffer.wrap(bytes, 0, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                if (containerLength < PtpConstants.HEADER_SIZE ||
                    containerLength > MAX_PTP_CONTAINER_SIZE) {
                    Log.e(TAG, "Invalid PTP container length: $containerLength")
                    pendingBulkIn = ByteArray(0)
                    return null
                }
            }
        }

        val bytes = output.toByteArray()
        if (bytes.size > containerLength) {
            val extra = bytes.copyOfRange(containerLength, bytes.size)
            pendingBulkIn = if (pendingBulkIn.isEmpty()) {
                extra
            } else {
                extra + pendingBulkIn
            }
        }

        val buffer = ByteBuffer.wrap(bytes, 0, containerLength).order(ByteOrder.LITTLE_ENDIAN)
        buffer.int // length
        val type = buffer.short.toInt() and 0xFFFF
        val code = buffer.short.toInt() and 0xFFFF
        val transactionId = buffer.int
        val payload = bytes.copyOfRange(PtpConstants.HEADER_SIZE, containerLength)
        return BulkContainer(type, code, transactionId, payload)
    }

    /**
     * Flush any stale data from the bulk IN pipe.
     * Call this after a sequence of commands that may have left data in the pipe.
     */
    fun flushPipe() = lock.withLock {
        val buf = ByteArray(512)
        var flushed = pendingBulkIn.size
        pendingBulkIn = ByteArray(0)
        while (true) {
            val read = connection.bulkTransfer(bulkIn, buf, buf.size, 100)
            if (read <= 0) break
            flushed += read
        }
        if (flushed > 0) {
            Log.d(TAG, "Flushed $flushed stale bytes from bulk IN pipe")
        }
    }

    /**
     * Clear HALT condition on both bulk endpoints.
     * Useful for recovering from stalled pipes after error conditions.
     */
    fun clearEndpoints() = lock.withLock {
        val clearOut = connection.controlTransfer(0x02, 0x01, 0, bulkOut.address, null, 0, 2000)
        val clearIn = connection.controlTransfer(0x02, 0x01, 0, bulkIn.address, null, 0, 2000)
        Log.d(TAG, "Clear endpoints: out=$clearOut, in=$clearIn")
        // Drain anything left (reentrant — this re-enters the lock we already hold)
        flushPipe()
    }

    /**
     * Read one PTP event from Interrupt IN.
     *
     * Keep this synchronous and under the same fair lock as Bulk IN/OUT.
     * Honor's Android 16 USB host implementation can crash natively inside
     * libusbhost usb_request_wait() when UsbRequest/requestWait is used for
     * this endpoint. A short, serialized bulkTransfer avoids that native path
     * and also prevents an event read from consuming data concurrently with a
     * multi-transfer PTP transaction.
     */
    fun readEvent(timeoutMs: Int = 100): PtpEvent? = lock.withLock {
        val endpoint = interruptIn ?: return@withLock null
        val bytes = ByteArray(PtpConstants.HEADER_SIZE + 12)
        val read = connection.bulkTransfer(endpoint, bytes, bytes.size, timeoutMs)
        if (read < PtpConstants.HEADER_SIZE) return@withLock null

        val buffer = ByteBuffer.wrap(bytes, 0, read).order(ByteOrder.LITTLE_ENDIAN)
        buffer.int // Container length; the USB transfer length is authoritative here.
        val type = buffer.short.toInt() and 0xFFFF
        val code = buffer.short.toInt() and 0xFFFF
        val txId = buffer.int

        if (type != PtpConstants.CONTAINER_TYPE_EVENT) return@withLock null

        val paramCount = ((read - PtpConstants.HEADER_SIZE) / 4).coerceAtMost(3)
        val params = IntArray(paramCount) { buffer.int }
        PtpEvent(code, txId, params)
    }

    private fun nextTransactionId(): Int {
        transactionId++
        if (transactionId > 0xFFFFFFF) transactionId = 1
        return transactionId
    }

    fun resetTransactionId() = lock.withLock {
        // PTP requires OpenSession itself to use transaction ID 0. All send
        // methods allocate the next ID before writing, so seed the counter at
        // -1; the following OpenSession gets 0 and the next operation gets 1.
        transactionId = -1
    }

    private data class BulkContainer(
        val type: Int,
        val code: Int,
        val transactionId: Int,
        val payload: ByteArray
    ) {
        fun toPtpResponse(): PtpResponse {
            val paramCount = payload.size / 4
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val params = IntArray(paramCount) { buffer.int }
            return PtpResponse(code, transactionId, params)
        }
    }
}

/** PTP response container. */
data class PtpResponse(
    val responseCode: Int,
    val transactionId: Int,
    val params: IntArray = IntArray(0),
    /**
     * True once the complete command/data-out sequence reached Bulk OUT.
     * A camera may execute a Sony command without returning a response, so
     * this is intentionally distinct from [isSuccess].
     */
    val commandDelivered: Boolean = true
) {
    val isSuccess: Boolean get() = PtpConstants.isSuccess(responseCode)
    override fun toString(): String = "PtpResponse(${PtpConstants.responseCodeName(responseCode)}, txId=$transactionId)"
}

/** PTP response with associated data payload. */
data class PtpDataResponse(
    val responseCode: Int,
    val transactionId: Int,
    val data: ByteArray
) {
    val isSuccess: Boolean get() = PtpConstants.isSuccess(responseCode)
    val dataSize: Int get() = data.size
}

/** PTP event from the interrupt endpoint. */
data class PtpEvent(
    val eventCode: Int,
    val transactionId: Int,
    val params: IntArray = IntArray(0)
)
