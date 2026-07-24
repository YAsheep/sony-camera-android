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
    }

    // Serializes all bulk/interrupt/control transfers on this endpoint set.
    // A single PTP operation is a sequence of transfers (command → data → response)
    // and must be atomic relative to any other caller, or we desync the pipe.
    // ReentrantLock — not Mutex — because callers are blocking and we want a
    // public method to be safe to call from within another public method.
    private val lock = ReentrantLock()

    private var transactionId = 0

    /**
     * Full USB/PTP reset sequence. Clears stalled endpoints and resets
     * the PTP session state on the camera. Required when reconnecting
     * to a camera that had a previous PTP session.
     */
    /**
     * Gentle PTP/USB reset. This is the known-good pre-hardening sequence —
     * single cancel + single device reset + clear halt + simple drain + a
     * short settle. It's calibrated for fresh plug-ins where the camera
     * just came out of enumeration and is in a clean state.
     *
     * It intentionally does NOT try to be smart about stale sessions —
     * aggressive secondary resets were observed to wedge the Sony a6600's
     * liveview engine even after the camera was power-cycled. Stale-session
     * recovery is handled by the heavy-reset retry in
     * [UsbCameraConnectionManager.connectToDevice]: if the first OpenSession
     * fails, that path releases the USB interface, closes the device
     * handle, reopens it, and calls resetDevice again — which is the USB
     * equivalent of unplug/replug and reliably recovers stale state without
     * harming the camera.
     */
    fun resetDevice() = lock.withLock {
        // Step 1: Cancel any pending PTP request (0x64)
        val cancelResult = connection.controlTransfer(
            0x21, 0x64, 0, 0, null, 0, 2000
        )
        Log.d(TAG, "PTP cancel request result: $cancelResult")
        Thread.sleep(100)

        // Step 2: PTP Device Reset (0x66)
        val resetResult = connection.controlTransfer(
            0x21, 0x66, 0, 0, null, 0, 5000
        )
        Log.d(TAG, "PTP device reset result: $resetResult")
        Thread.sleep(100)

        // Step 3: Clear HALT on bulk endpoints
        val clearOut = connection.controlTransfer(
            0x02, 0x01, 0, bulkOut.address, null, 0, 2000
        )
        Log.d(TAG, "Clear HALT on bulkOut (addr=${bulkOut.address}): $clearOut")

        val clearIn = connection.controlTransfer(
            0x02, 0x01, 0, bulkIn.address, null, 0, 2000
        )
        Log.d(TAG, "Clear HALT on bulkIn (addr=${bulkIn.address}): $clearIn")

        // Step 4: Drain stale data from bulk IN (leftovers from MTP service
        // probing, or a previously-interrupted transfer).
        val drainBuf = ByteArray(512)
        var drained = 0
        while (true) {
            val read = connection.bulkTransfer(bulkIn, drainBuf, drainBuf.size, 200)
            if (read <= 0) break
            drained += read
        }
        if (drained > 0) Log.d(TAG, "Drained $drained stale bytes from bulk IN")

        // Step 5: Short settle so the camera's PTP state machine is ready
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
        if (sent < 0) {
            Log.e(TAG, "Failed to send command 0x${operationCode.toString(16)}, bulkTransfer returned $sent")
            return@withLock PtpResponse(PtpConstants.RESP_GENERAL_ERROR, txId)
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

        val sent = connection.bulkTransfer(bulkOut, buffer.array(), containerLength, PtpConstants.USB_TIMEOUT_MS)
        if (sent < 0) {
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
        if (sent < 0) {
            return@withLock PtpDataResponse(PtpConstants.RESP_GENERAL_ERROR, txId, ByteArray(0))
        }

        // Read with short timeout
        val headerBuf = ByteArray(PtpConstants.USB_TRANSFER_BUFFER_SIZE)
        val read = connection.bulkTransfer(bulkIn, headerBuf, headerBuf.size, timeoutMs)

        if (read < PtpConstants.HEADER_SIZE) {
            return@withLock PtpDataResponse(PtpConstants.RESP_GENERAL_ERROR, txId, ByteArray(0))
        }

        val bb = ByteBuffer.wrap(headerBuf, 0, read).order(ByteOrder.LITTLE_ENDIAN)
        val totalLength = bb.getInt()
        val type = bb.getShort().toInt() and 0xFFFF
        val code = bb.getShort().toInt() and 0xFFFF
        val responseTxId = bb.getInt()

        if (type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
            return@withLock PtpDataResponse(code, responseTxId, ByteArray(0))
        }

        if (type != PtpConstants.CONTAINER_TYPE_DATA) {
            return@withLock PtpDataResponse(PtpConstants.RESP_GENERAL_ERROR, txId, ByteArray(0))
        }

        // Collect data
        val dataSize = totalLength - PtpConstants.HEADER_SIZE
        val output = ByteArrayOutputStream(dataSize.coerceAtMost(PtpConstants.USB_TRANSFER_BUFFER_SIZE))
        val firstChunkSize = read - PtpConstants.HEADER_SIZE
        if (firstChunkSize > 0) {
            output.write(headerBuf, PtpConstants.HEADER_SIZE, firstChunkSize)
        }

        var totalRead = firstChunkSize
        while (totalRead < dataSize) {
            val chunkRead = connection.bulkTransfer(bulkIn, headerBuf, headerBuf.size, timeoutMs)
            if (chunkRead <= 0) break
            output.write(headerBuf, 0, chunkRead)
            totalRead += chunkRead
        }

        val data = output.toByteArray()
        val response = readResponse(txId)
        PtpDataResponse(response.responseCode, responseTxId, data)
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

        var sent = connection.bulkTransfer(bulkOut, cmdBuffer.array(), cmdLength, PtpConstants.USB_TIMEOUT_MS)
        if (sent < 0) {
            Log.e(TAG, "DataOut cmd 0x${operationCode.toString(16)} send failed (bulkTransfer=$sent)")
            return@withLock PtpResponse(PtpConstants.RESP_GENERAL_ERROR, txId)
        }

        // Send data container
        val dataLength = PtpConstants.HEADER_SIZE + data.size
        val dataBuffer = ByteBuffer.allocate(dataLength).order(ByteOrder.LITTLE_ENDIAN)
        dataBuffer.putInt(dataLength)
        dataBuffer.putShort(PtpConstants.CONTAINER_TYPE_DATA.toShort())
        dataBuffer.putShort(operationCode.toShort())
        dataBuffer.putInt(txId)
        dataBuffer.put(data)

        sent = connection.bulkTransfer(bulkOut, dataBuffer.array(), dataLength, PtpConstants.USB_TIMEOUT_MS)
        if (sent < 0) {
            Log.e(TAG, "DataOut data phase send failed (bulkTransfer=$sent)")
            return@withLock PtpResponse(PtpConstants.RESP_GENERAL_ERROR, txId)
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
        if (sent < 0) {
            Log.e(TAG, "DataOutIn cmd 0x${operationCode.toString(16)} send failed")
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

        sent = connection.bulkTransfer(bulkOut, dataOutBuffer.array(), dataOutLength, timeoutMs)
        if (sent < 0) {
            Log.e(TAG, "DataOutIn data-out phase send failed")
            return@withLock PtpDataResponse(PtpConstants.RESP_GENERAL_ERROR, txId, ByteArray(0))
        }

        // 3. Read data-in phase + response
        readDataAndResponse(txId)
    }

    /**
     * Read a PTP response with a short timeout (500ms).
     * Sony SetControlDeviceB commands may not send a response at all — the camera
     * accepts the command and executes it but stalls the IN endpoint.
     * Using a short timeout prevents blocking for 5+ seconds per command.
     */
    private fun readResponseQuick(expectedTxId: Int): PtpResponse {
        val buffer = ByteArray(PtpConstants.HEADER_SIZE + 20)
        val read = connection.bulkTransfer(bulkIn, buffer, buffer.size, 500)

        if (read < PtpConstants.HEADER_SIZE) {
            // No response within 500ms — camera may have accepted command silently
            return PtpResponse(PtpConstants.RESP_GENERAL_ERROR, expectedTxId)
        }

        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val length = bb.getInt()
        val type = bb.getShort().toInt() and 0xFFFF
        val code = bb.getShort().toInt() and 0xFFFF
        val txId = bb.getInt()

        if (type != PtpConstants.CONTAINER_TYPE_RESPONSE) {
            if (type == PtpConstants.CONTAINER_TYPE_DATA) {
                drainData(length, read)
                return readResponseQuick(expectedTxId)
            }
        }

        val paramCount = (read - PtpConstants.HEADER_SIZE) / 4
        val params = IntArray(paramCount) { bb.getInt() }
        return PtpResponse(code, txId, params)
    }

    /**
     * Read a PTP response container from the device.
     */
    private fun readResponse(
        expectedTxId: Int,
        timeoutMs: Int = PtpConstants.USB_TIMEOUT_MS
    ): PtpResponse {
        val buffer = ByteArray(PtpConstants.HEADER_SIZE + 20) // Header + up to 5 params
        val read = connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMs)

        if (read < PtpConstants.HEADER_SIZE) {
            Log.e(TAG, "Short read for response: $read bytes")
            return PtpResponse(PtpConstants.RESP_GENERAL_ERROR, expectedTxId)
        }

        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val length = bb.getInt()
        val type = bb.getShort().toInt() and 0xFFFF
        val code = bb.getShort().toInt() and 0xFFFF
        val txId = bb.getInt()

        if (type != PtpConstants.CONTAINER_TYPE_RESPONSE) {
            Log.w(TAG, "Expected response container, got type $type")
            // Might be a data container — read and discard, then read actual response
            if (type == PtpConstants.CONTAINER_TYPE_DATA) {
                drainData(length, read)
                return readResponse(expectedTxId)
            }
        }

        // Extract response parameters
        val paramCount = (read - PtpConstants.HEADER_SIZE) / 4
        val params = IntArray(paramCount) { bb.getInt() }

        return PtpResponse(code, txId, params)
    }

    /**
     * Read data phase followed by response.
     */
    private fun readDataAndResponse(expectedTxId: Int): PtpDataResponse {
        val headerBuf = ByteArray(PtpConstants.USB_TRANSFER_BUFFER_SIZE)
        val read = connection.bulkTransfer(bulkIn, headerBuf, headerBuf.size, PtpConstants.USB_TIMEOUT_MS * 2)

        if (read < PtpConstants.HEADER_SIZE) {
            Log.e(TAG, "Short read for data: $read bytes")
            return PtpDataResponse(PtpConstants.RESP_GENERAL_ERROR, expectedTxId, ByteArray(0))
        }

        val bb = ByteBuffer.wrap(headerBuf, 0, read).order(ByteOrder.LITTLE_ENDIAN)
        val totalLength = bb.getInt()
        val type = bb.getShort().toInt() and 0xFFFF
        val code = bb.getShort().toInt() and 0xFFFF
        val txId = bb.getInt()

        if (type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
            // No data phase — just a response (e.g., error)
            return PtpDataResponse(code, txId, ByteArray(0))
        }

        if (type != PtpConstants.CONTAINER_TYPE_DATA) {
            Log.w(TAG, "Expected data container, got type $type")
            return PtpDataResponse(PtpConstants.RESP_GENERAL_ERROR, expectedTxId, ByteArray(0))
        }

        // Collect all data
        val dataSize = totalLength - PtpConstants.HEADER_SIZE
        val output = ByteArrayOutputStream(dataSize.coerceAtMost(PtpConstants.USB_TRANSFER_BUFFER_SIZE))

        // First chunk (after header)
        val firstChunkSize = read - PtpConstants.HEADER_SIZE
        if (firstChunkSize > 0) {
            output.write(headerBuf, PtpConstants.HEADER_SIZE, firstChunkSize)
        }

        // Read remaining chunks if data spans multiple USB transfers
        var totalRead = firstChunkSize
        while (totalRead < dataSize) {
            val chunkRead = connection.bulkTransfer(
                bulkIn, headerBuf, headerBuf.size,
                PtpConstants.USB_TIMEOUT_MS
            )
            if (chunkRead <= 0) break
            output.write(headerBuf, 0, chunkRead)
            totalRead += chunkRead
        }

        val data = output.toByteArray()

        // Now read the response container
        val response = readResponse(expectedTxId)

        return PtpDataResponse(response.responseCode, txId, data)
    }

    /**
     * Drain remaining data from a data container we don't need.
     */
    private fun drainData(totalLength: Int, alreadyRead: Int) {
        val remaining = totalLength - alreadyRead
        if (remaining <= 0) return

        val buf = ByteArray(PtpConstants.USB_TRANSFER_BUFFER_SIZE)
        var left = remaining
        while (left > 0) {
            val read = connection.bulkTransfer(bulkIn, buf, buf.size, PtpConstants.USB_TIMEOUT_MS)
            if (read <= 0) break
            left -= read
        }
    }

    /**
     * Flush any stale data from the bulk IN pipe.
     * Call this after a sequence of commands that may have left data in the pipe.
     */
    fun flushPipe() = lock.withLock {
        val buf = ByteArray(512)
        var flushed = 0
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
     * Read a PTP event from the interrupt endpoint (non-blocking).
     * Returns null if no event is available.
     */
    fun readEvent(timeoutMs: Int = 100): PtpEvent? = lock.withLock {
        val endpoint = interruptIn ?: return@withLock null
        val buffer = ByteArray(PtpConstants.HEADER_SIZE + 12) // Header + up to 3 params
        val read = connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)

        if (read < PtpConstants.HEADER_SIZE) return@withLock null

        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val length = bb.getInt()
        val type = bb.getShort().toInt() and 0xFFFF
        val code = bb.getShort().toInt() and 0xFFFF
        val txId = bb.getInt()

        if (type != PtpConstants.CONTAINER_TYPE_EVENT) return@withLock null

        val paramCount = (read - PtpConstants.HEADER_SIZE) / 4
        val params = IntArray(paramCount) { bb.getInt() }

        PtpEvent(code, txId, params)
    }

    private fun nextTransactionId(): Int {
        transactionId++
        if (transactionId > 0xFFFFFFF) transactionId = 1
        return transactionId
    }

    fun resetTransactionId() = lock.withLock {
        transactionId = 0
    }
}

/** PTP response container. */
data class PtpResponse(
    val responseCode: Int,
    val transactionId: Int,
    val params: IntArray = IntArray(0)
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
