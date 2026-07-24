package io.github.gallo.sonycamera.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import io.github.gallo.sonycamera.CameraConnectionManager
import io.github.gallo.sonycamera.CameraConnectionState
import io.github.gallo.sonycamera.CameraEvent
import io.github.gallo.sonycamera.CameraOperationResult
import io.github.gallo.sonycamera.ptp.PtpConstants
import io.github.gallo.sonycamera.ptp.PtpTransport
import io.github.gallo.sonycamera.ptp.SonyPtpCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * USB PTP camera connection engine for Sony cameras.
 *
 * Implements [CameraConnectionManager] using Android's USB Host API and
 * PTP protocol for camera communication. Provides:
 * - USB device detection and permission handling
 * - PTP session management
 * - Liveview frame streaming via [liveviewFrames] flow
 * - Photo capture and download
 *
 * This is NOT a DI singleton — it is instantiated and solely owned by
 * [CameraConnectionService], which manages its lifecycle (foreground
 * service, watchdog, process-death recovery). Call [destroy] to tear down.
 */
class UsbCameraConnectionManager(
    private val context: Context
) : CameraConnectionManager {

    companion object {
        private const val TAG = "UsbCameraManager"
        private const val ACTION_USB_PERMISSION = "io.github.gallo.sonycamera.USB_PERMISSION"
        // Sony USB liveview typically runs ~10-15 fps due to USB bulk transfer overhead.
        // Polling faster than the camera can produce frames just wastes CPU.
        private const val LIVEVIEW_MIN_FRAME_INTERVAL_MS = 30L // ~33 fps max
        // How long we hold the UI in "reconnecting" after a USB detach before giving up.
        // Accommodates a bumped cable, a brief USB hub reset, or a camera auto-sleep wake.
        private const val RECONNECT_GRACE_MS = 7_000L
        // How often we poll usbManager.deviceList during the grace window.
        private const val REATTACH_POLL_INTERVAL_MS = 400L
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Separate scope for USB teardown launches. Stays alive even after
     * [destroy] cancels the main [scope], so the graceful end-session
     * commands (priority release + PTP CloseSession) always get a chance to
     * reach the camera before the connection is torn down.
     */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── State ──
    private val _connectionState = MutableStateFlow<CameraConnectionState>(CameraConnectionState.Disconnected)
    override val connectionState: StateFlow<CameraConnectionState> = _connectionState.asStateFlow()

    private val _cameraName = MutableStateFlow<String?>(null)
    override val cameraName: StateFlow<String?> = _cameraName.asStateFlow()

    private val _events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<CameraEvent> = _events.asSharedFlow()

    private val _liveviewFrames = MutableSharedFlow<Bitmap>(
        replay = 1,
        extraBufferCapacity = 2,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override val liveviewFrames: SharedFlow<Bitmap> = _liveviewFrames

    // ── USB resources ──
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var ptpInterface: UsbInterface? = null
    private var ptpCamera: SonyPtpCamera? = null
    private var liveviewJob: Job? = null
    private var isLiveviewActive = false

    // In-flight connect job. Tracking it lets disconnect / detach cancel a
    // connect attempt that's mid-handshake so its coroutine can run its
    // finally-block cleanup instead of leaking a claimed interface.
    private var connectJob: Job? = null

    // Reconnect bookkeeping. When the cable is physically detached we don't
    // immediately surface ConnectionLost — we hold the UI in "Connecting" for
    // RECONNECT_GRACE_MS so a quickly-reattached cable resumes without a
    // round-trip back through the scanner screen.
    @Volatile private var isAwaitingReattach = false
    private var reconnectTimeoutJob: Job? = null

    // Decode options for liveview display (RGB_565 for efficiency)
    private val liveviewDecodeOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
    }

    // ── USB device detection ──
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        Log.d(TAG, "USB permission granted for ${device.deviceName}")
                        connectJob?.cancel()
                        connectJob = scope.launch { connectToDevice(device) }
                    } else {
                        Log.w(TAG, "USB permission denied")
                        _connectionState.value = CameraConnectionState.Error(
                            "USB access not allowed. Unplug the camera, replug, and tap Allow on the prompt."
                        )
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device?.vendorId == PtpConstants.SONY_VENDOR_ID) {
                        handleSonyDetached()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null &&
                        device.vendorId == PtpConstants.SONY_VENDOR_ID &&
                        hasPtpInterface(device)
                    ) {
                        handleSonyAttached(device)
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    /**
     * Handle a Sony camera being detached from USB.
     *
     * If we were actively connected, tear down USB resources but hold the
     * connection state at [CameraConnectionState.Connecting] for a grace
     * window so a bumped cable or brief hub reset can re-establish without
     * bouncing the UI back to the scanner. If the grace window expires, we
     * fall through to the original ConnectionLost behavior.
     */
    private fun handleSonyDetached() {
        val wasConnected = _connectionState.value is CameraConnectionState.Ready ||
                _connectionState.value is CameraConnectionState.Initializing
        if (!wasConnected) {
            // Not actively in use — nothing to reconnect to. Existing cleanup is sufficient.
            if (_connectionState.value !is CameraConnectionState.Disconnected) {
                Log.d(TAG, "Sony camera detached while not in use")
                disconnect()
                scope.launch { _events.emit(CameraEvent.ConnectionLost) }
            }
            return
        }

        Log.d(TAG, "Sony camera detached — entering reconnect grace window (${RECONNECT_GRACE_MS}ms)")
        isAwaitingReattach = true
        closeUsbResources()
        _connectionState.value = CameraConnectionState.Connecting

        reconnectTimeoutJob?.cancel()
        reconnectTimeoutJob = scope.launch {
            // Poll usbManager.deviceList for a reattached Sony camera.
            // ACTION_USB_DEVICE_ATTACHED is not reliably delivered to runtime
            // receivers (and often skipped for manifest activity intent-filters
            // when the app is already foreground), so polling is the most
            // robust way to detect a re-plugged cable within the grace window.
            val deadline = System.currentTimeMillis() + RECONNECT_GRACE_MS
            while (isAwaitingReattach && System.currentTimeMillis() < deadline) {
                val reattached = findSonyCamera()
                if (reattached != null) {
                    Log.d(TAG, "Sony camera reattached (poll) — auto-reconnecting")
                    isAwaitingReattach = false
                    connectToCamera(reattached)
                    return@launch
                }
                delay(REATTACH_POLL_INTERVAL_MS)
            }
            if (isAwaitingReattach) {
                isAwaitingReattach = false
                Log.w(TAG, "Reconnect grace window expired — giving up")
                _cameraName.value = null
                _connectionState.value = CameraConnectionState.Disconnected
                _events.emit(CameraEvent.ConnectionLost)
            }
        }
    }

    /**
     * Handle a Sony camera being attached. If we're inside the reconnect
     * grace window from a previous detach, silently reconnect. Otherwise
     * ignore — initial connection is driven by the scanner screen.
     *
     * USB permission is revoked on detach. The manifest intent-filter normally
     * auto-grants it again when Android delivers the attach intent, but that
     * grant can lag a few hundred ms behind the intent itself. We briefly
     * wait for the grant to land before falling back to requestPermission(),
     * which would pop a dialog that defeats the "seamless reconnect" goal.
     */
    private fun handleSonyAttached(device: UsbDevice) {
        if (!isAwaitingReattach) return
        Log.d(TAG, "Sony camera reattached — waiting for permission auto-grant")
        isAwaitingReattach = false
        reconnectTimeoutJob?.cancel()
        reconnectTimeoutJob = scope.launch {
            val deadline = System.currentTimeMillis() + 1500
            while (!usbManager.hasPermission(device) && System.currentTimeMillis() < deadline) {
                delay(100)
            }
            if (usbManager.hasPermission(device)) {
                Log.d(TAG, "USB permission present — auto-reconnecting")
            } else {
                Log.d(TAG, "USB permission not auto-granted — requesting")
            }
            connectToCamera(device)
        }
    }

    /**
     * Invoked by MainActivity when Android delivers a USB_DEVICE_ATTACHED
     * intent (either via onCreate for a cold launch or onNewIntent while
     * running). The attach broadcast is NOT delivered to runtime-registered
     * BroadcastReceivers — only to Activities via manifest intent-filter —
     * so this forwarder is the only way we learn about a reattach.
     */
    fun onUsbDeviceAttached(device: UsbDevice) {
        if (device.vendorId != PtpConstants.SONY_VENDOR_ID) return
        if (!hasPtpInterface(device)) return
        handleSonyAttached(device)
    }

    // ══════════════════════════════════════════════
    // CameraConnectionManager implementation
    // ══════════════════════════════════════════════

    override suspend fun startLiveview(): CameraOperationResult {
        if (ptpCamera == null) return CameraOperationResult.Failure("Camera not connected")
        if (isLiveviewActive) return CameraOperationResult.Success

        isLiveviewActive = true
        liveviewJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting USB liveview loop (GetObject 0xFFFFC002)")

            var frameCount = 0L
            var errorCount = 0L
            var lastLogTime = System.currentTimeMillis()
            var consecutiveErrors = 0
            var hasEverGottenFrame = false
            var pipeRecoveryAttempts = 0
            // Time-based stall detection: trip pipe recovery when we haven't
            // seen a successful frame in a long time, rather than after a few
            // denials in a row. Normal Sony behavior during zoom / AF bursts
            // is to produce denials between frames; counting them as a stall
            // made the FPS collapse exactly when the camera was busy.
            var lastFrameTime = System.currentTimeMillis()
            val stallTimeoutMs = 2_000L
            val initStallTimeoutMs = 5_000L
            // Wedged-liveview watchdog: after a reconnect, the camera can
            // get into a state where PTP works but liveview produces 100%
            // denials. clearEndpoints doesn't fix it; only a physical
            // unplug does. After NEVER seeing a first frame for this many
            // milliseconds, give up and surface ConnectionLost so the UI
            // can prompt the user to unplug/replug.
            val neverGotFrameFatalMs = 18_000L
            val liveviewStartTime = System.currentTimeMillis()

            while (isActive && isLiveviewActive) {
                try {
                    val frameStart = System.currentTimeMillis()
                    val jpeg = ptpCamera?.getLiveViewFrame()

                    if (jpeg != null) {
                        val bitmap = BitmapFactory.decodeByteArray(
                            jpeg, 0, jpeg.size, liveviewDecodeOptions
                        )
                        if (bitmap != null) {
                            _liveviewFrames.emit(bitmap)
                        }
                        frameCount++
                        consecutiveErrors = 0
                        hasEverGottenFrame = true
                        pipeRecoveryAttempts = 0
                        lastFrameTime = System.currentTimeMillis()

                        // Pace: ensure minimum interval between successful frames
                        val elapsed = System.currentTimeMillis() - frameStart
                        val sleepMs = LIVEVIEW_MIN_FRAME_INTERVAL_MS - elapsed
                        if (sleepMs > 0) delay(sleepMs)
                    } else {
                        errorCount++
                        consecutiveErrors++

                        // Fatal case: we've never seen a single frame and the
                        // camera has been denying us for too long. This is the
                        // wedged-liveview state that happens after some app
                        // swipe-away → reconnect sequences. Only a physical
                        // unplug clears it; surface ConnectionLost so the
                        // user is prompted to do that.
                        val sinceStart = System.currentTimeMillis() - liveviewStartTime
                        if (!hasEverGottenFrame && sinceStart > neverGotFrameFatalMs) {
                            Log.e(TAG, "Liveview never produced a frame in ${sinceStart}ms — " +
                                    "camera state wedged. Surfacing ConnectionLost.")
                            isLiveviewActive = false
                            _events.emit(CameraEvent.ConnectionLost)
                            break
                        }

                        val timeSinceFrame = System.currentTimeMillis() - lastFrameTime
                        val stallThreshold = if (hasEverGottenFrame) stallTimeoutMs else initStallTimeoutMs
                        if (timeSinceFrame > stallThreshold) {
                            pipeRecoveryAttempts++
                            Log.w(TAG, "Liveview stall (no frame in ${timeSinceFrame}ms), " +
                                    "clearing endpoints (recovery attempt $pipeRecoveryAttempts)")
                            ptpCamera?.flushAndResetPipe()
                            delay(200)
                            lastFrameTime = System.currentTimeMillis()
                            consecutiveErrors = 0
                        }

                        // Poll aggressively during slow-frame bursts so we catch
                        // the next available frame without adding latency.
                        delay(10)
                    }

                    // Log stats every 5 seconds
                    val now = System.currentTimeMillis()
                    if (now - lastLogTime >= 5000) {
                        val elapsed = (now - lastLogTime) / 1000.0
                        val fps = frameCount / elapsed
                        Log.d(TAG, "USB liveview: %.1f fps, %d errors (consecutive=%d, recoveries=%d)".format(
                            fps, errorCount, consecutiveErrors, pipeRecoveryAttempts))
                        frameCount = 0
                        errorCount = 0
                        lastLogTime = now
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Must rethrow — swallowing this breaks coroutine cancellation
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Liveview frame error: ${e.message}")
                    delay(200)
                }
            }
            Log.d(TAG, "USB liveview loop ended")
        }

        return CameraOperationResult.Success
    }

    override suspend fun stopLiveview(): CameraOperationResult {
        isLiveviewActive = false
        liveviewJob?.cancel()
        liveviewJob = null
        return CameraOperationResult.Success
    }

    override suspend fun takePhoto(): CameraOperationResult = try {
        // Hard ceiling on total capture time. The retry logic below bounds
        // itself at ~18.5s (10s + 0.5s + 8s queue waits), so 25s absorbs
        // normal jitter without ever letting a truly stuck call hang the UI.
        kotlinx.coroutines.withTimeout(25_000) {
            takePhotoInner()
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        Log.e(TAG, "takePhoto timed out after 25s")
        CameraOperationResult.Failure("Capture took too long — please try again")
    }

    private suspend fun takePhotoInner(): CameraOperationResult = withContext(Dispatchers.IO) {
        val camera = ptpCamera
            ?: return@withContext CameraOperationResult.Failure("Camera not connected")

        // Liveview is intentionally NOT stopped here. It keeps running through
        // initiateCapture()'s autofocus phase so the preview stays live right
        // up to the shutter — it is torn down inside the shutter callback
        // below, at the exact full-press moment. PtpTransport serialises every
        // transaction, so at most one in-flight liveview frame can overlap the
        // full-press; the capture exposure, readout, and multi-second photo
        // download that follow run with liveview already stopped.
        val wasLiveview = isLiveviewActive

        try {
            // Two attempts max. Each: fire shutter, then wait for the photo to
            // appear in Sony's PhotoTransferQueue and download it. The most
            // common failure mode we've seen is the camera silently dropping
            // the shutter (queue count stays at 0). Re-firing recovers it.
            // There is no silent fallback to a liveview thumbnail — if we
            // can't deliver a full-res frame the caller must know.
            // initiateCapture() invokes its callback at the exact full-press
            // moment — the real shutter — so the UI flash coincides with the
            // capture instead of leading it. Signal only once across retries.
            var shutterSignalled = false
            for (attempt in 1..2) {
                Log.d(TAG, "Capture attempt $attempt/2")

                val captureFired = camera.initiateCapture {
                    if (!shutterSignalled) {
                        shutterSignalled = true
                        // Real shutter moment: stop liveview so it doesn't
                        // contend with the capture, then flash the UI.
                        isLiveviewActive = false
                        liveviewJob?.cancel()
                        liveviewJob = null
                        _events.tryEmit(CameraEvent.ShutterFired)
                        Log.d(TAG, "Shutter fired — liveview stopped, flash signalled")
                    }
                }
                if (!captureFired) {
                    Log.w(TAG, "Shutter command failed on attempt $attempt")
                    if (attempt < 2) {
                        delay(500)
                        continue
                    }
                    return@withContext CameraOperationResult.Failure(
                        "Camera didn't respond to shutter — please try again"
                    )
                }

                val queueWaitMs = if (attempt == 1) 10_000L else 8_000L
                val fullResJpeg = try {
                    camera.downloadQueuedPhoto(maxWaitMs = queueWaitMs)
                } catch (e: Exception) {
                    Log.w(TAG, "Download error on attempt $attempt: ${e.message}")
                    null
                }

                // Size floor: a real Sony A6600 JPEG is several MB. Anything
                // under ~200KB is either Sony's error stub or a truncated
                // transfer — treat as failure so we retry or fail loudly
                // rather than emitting a visibly-bad photo.
                if (fullResJpeg != null && fullResJpeg.size >= 200_000) {
                    val bitmap = BitmapFactory.decodeByteArray(fullResJpeg, 0, fullResJpeg.size)
                    if (bitmap != null) {
                        Log.d(TAG, "Photo captured (full-res): " +
                                "${fullResJpeg.size / 1024}KB, ${bitmap.width}x${bitmap.height} " +
                                "on attempt $attempt")
                        _events.emit(CameraEvent.PhotoCaptured(bitmap))
                        return@withContext CameraOperationResult.Success
                    }
                    Log.w(TAG, "Full-res JPEG decode failed on attempt $attempt " +
                            "(size=${fullResJpeg.size}B)")
                } else {
                    Log.w(TAG, "Full-res download failed on attempt $attempt " +
                            "(size=${fullResJpeg?.size ?: 0}B)")
                }

                if (attempt < 2) delay(500)
            }

            // Both attempts failed — surface a clear error. No thumbnail fallback.
            CameraOperationResult.Failure("Photo didn't save — please try again")
        } catch (e: Exception) {
            Log.e(TAG, "Photo capture error", e)
            // Never surface raw exception text to the user — those messages
            // are aimed at developers ("bulkTransfer returned -1"). Keep the
            // user-facing string consistent with the other capture failures.
            CameraOperationResult.Failure("Photo capture failed — please try again")
        } finally {
            if (wasLiveview) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    delay(1500)
                    startLiveview()
                }
            }
        }
    }

    override fun disconnect() {
        Log.d(TAG, "Disconnecting USB camera")
        // User-initiated disconnect always cancels a pending reconnect.
        isAwaitingReattach = false
        reconnectTimeoutJob?.cancel()
        reconnectTimeoutJob = null

        closeUsbResources()
        _cameraName.value = null
        _connectionState.value = CameraConnectionState.Disconnected
    }

    /**
     * Permanently tear down this engine. Called by the owning service in
     * onDestroy: unregisters the USB receiver, releases USB resources, and
     * cancels all coroutines. The instance must not be used afterwards.
     */
    fun destroy() {
        Log.d(TAG, "Destroying USB camera engine")
        isAwaitingReattach = false
        reconnectTimeoutJob?.cancel()
        reconnectTimeoutJob = null
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "usbReceiver already unregistered")
        }
        closeUsbResources()
        _cameraName.value = null
        _connectionState.value = CameraConnectionState.Disconnected
        scope.cancel()
    }

    /**
     * Release USB resources without touching the connection state flow.
     * Used both by [disconnect] (user intent) and the reconnect grace flow
     * (where we want to hold the UI in Connecting while waiting for reattach).
     *
     * Steals the current resource handles into locals, nulls the fields
     * immediately, and does the actual close work on Dispatchers.IO so
     * closeSession()'s USB bulk transfer doesn't block the caller's thread.
     * Since nothing else can use the handles after they're nulled out, doing
     * the close asynchronously is safe.
     */
    private fun closeUsbResources() {
        isLiveviewActive = false
        liveviewJob?.cancel()
        liveviewJob = null

        // Cancel any in-flight connect so its finally-block unwinds the
        // resources it allocated rather than silently committing them after
        // we've already decided to tear down.
        connectJob?.cancel()
        connectJob = null

        val camera = ptpCamera
        val conn = usbConnection
        val iface = ptpInterface
        ptpCamera = null
        usbConnection = null
        ptpInterface = null
        usbDevice = null

        if (camera == null && conn == null) {
            Log.d(TAG, "closeUsbResources: nothing to tear down (camera/conn already null)")
            return
        }

        // Run the teardown on a scope that survives engine.destroy()'s
        // scope.cancel — otherwise endSession can be cancelled before its
        // USB transactions reach the camera.
        teardownScope.launch {
            Log.d(TAG, "USB teardown: ending camera session")
            try {
                // Graceful end: release Sony priority + PTP CloseSession so
                // the camera knows we're done and returns to normal operation.
                camera?.endSession()
            } catch (e: Exception) {
                Log.w(TAG, "endSession during teardown: ${e.message}")
            }
            try {
                if (iface != null) conn?.releaseInterface(iface)
            } catch (e: Exception) {
                Log.w(TAG, "releaseInterface during teardown: ${e.message}")
            }
            try {
                conn?.close()
            } catch (e: Exception) {
                Log.w(TAG, "connection close during teardown: ${e.message}")
            }
        }
    }

    override fun isReady(): Boolean = _connectionState.value is CameraConnectionState.Ready

    // ══════════════════════════════════════════════
    // USB-specific methods
    // ══════════════════════════════════════════════

    /**
     * Scan for attached Sony PTP cameras.
     */
    fun findSonyCamera(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == PtpConstants.SONY_VENDOR_ID && hasPtpInterface(device)
        }
    }

    /**
     * Connect to a Sony camera. Requests USB permission if needed.
     */
    fun connectToCamera(device: UsbDevice? = null) {
        val target = device ?: findSonyCamera()
        if (target == null) {
            _connectionState.value = CameraConnectionState.Error(
                "No camera detected. Check the USB cable is plugged in at both ends."
            )
            return
        }

        _connectionState.value = CameraConnectionState.Connecting

        if (usbManager.hasPermission(target)) {
            connectJob?.cancel()
            connectJob = scope.launch { connectToDevice(target) }
        } else {
            Log.d(TAG, "Requesting USB permission for ${target.deviceName}")
            // Explicit intent required on Android 14+ (targeting U+)
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE or
                        PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(target, permissionIntent)
        }
    }

    /**
     * Internal: connect to a USB device after permission is granted.
     *
     * All claimed/opened resources are held in local vars until the handshake
     * fully succeeds; only then do we publish them to fields. Any early return
     * (missing endpoints, openSession failure, cancellation, exception) runs
     * through finally and releases whatever we allocated — no more leaked
     * interfaces or connections on partial failure.
     */
    private suspend fun connectToDevice(device: UsbDevice) = withContext(Dispatchers.IO) {
        var localConn: UsbDeviceConnection? = null
        var localIface: UsbInterface? = null
        var ifaceClaimed = false
        var localCamera: SonyPtpCamera? = null
        var committed = false

        try {
            _connectionState.value = CameraConnectionState.Connecting

            // Log all device interfaces for debugging
            Log.d(TAG, "USB Device: vendor=0x${device.vendorId.toString(16)}, product=0x${device.productId.toString(16)}, class=${device.deviceClass}")
            Log.d(TAG, "  Interfaces: ${device.interfaceCount}")
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                Log.d(TAG, "  Interface $i: class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} protocol=${intf.interfaceProtocol} endpoints=${intf.endpointCount}")
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    Log.d(TAG, "    Endpoint $e: type=${ep.type} dir=${ep.direction} maxPacket=${ep.maxPacketSize}")
                }
            }

            // Find PTP interface
            localIface = findPtpInterface(device)
            if (localIface == null) {
                _connectionState.value = CameraConnectionState.Error(
                    "Camera USB mode is wrong. In the camera menu, set USB Connection to 'PC Remote' or 'Auto'."
                )
                return@withContext
            }

            // Open connection
            localConn = usbManager.openDevice(device)
            if (localConn == null) {
                _connectionState.value = CameraConnectionState.Error(
                    "Couldn't open the camera. Unplug the USB cable, wait a moment, then plug it back in."
                )
                return@withContext
            }

            // Force-claim the interface. The `true` parameter detaches any kernel
            // driver (e.g., Android's MTP service) that may have auto-claimed it.
            // We may need multiple attempts as the MTP service can re-attach.
            for (attempt in 1..3) {
                if (localConn.claimInterface(localIface, true)) {
                    ifaceClaimed = true
                    Log.d(TAG, "Claimed PTP interface on attempt $attempt")
                    break
                }
                Log.w(TAG, "Failed to claim interface, attempt $attempt/3, retrying...")
                Thread.sleep(500)
            }
            if (!ifaceClaimed) {
                _connectionState.value = CameraConnectionState.Error(
                    "Another app is using the camera. Close other photo apps, unplug the cable, and try again."
                )
                return@withContext
            }

            // Find endpoints
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            var interruptIn: UsbEndpoint? = null

            for (i in 0 until localIface.endpointCount) {
                val ep = localIface.getEndpoint(i)
                when {
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            ep.direction == UsbConstants.USB_DIR_IN -> bulkIn = ep
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            ep.direction == UsbConstants.USB_DIR_OUT -> bulkOut = ep
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                            ep.direction == UsbConstants.USB_DIR_IN -> interruptIn = ep
                }
            }

            if (bulkIn == null || bulkOut == null) {
                _connectionState.value = CameraConnectionState.Error(
                    "Camera USB mode is wrong. In the camera menu, set USB Connection to 'PC Remote' or 'Auto'."
                )
                return@withContext
            }

            _connectionState.value = CameraConnectionState.Initializing

            // Single-attempt PTP handshake. We intentionally do NOT retry
            // by closing and reopening the USB device — that "heavy reset"
            // was observed to push the Sony a6600 firmware into a wedged
            // state where every subsequent command times out with General
            // Error, requiring a battery pull to recover. When the camera
            // is mid-session (swipe-away scenario) it keeps pumping liveview
            // data into the pipe; closing+reopening just re-fills the pipe
            // and confuses the camera further. If this attempt fails, we
            // surface a clear error asking the user to unplug/replug — the
            // physical detach-reattach path is reliable and triggers our
            // auto-reconnect flow cleanly.
            val transport = PtpTransport(localConn, bulkOut, bulkIn, interruptIn)
            Log.d(TAG, "Sending PTP device reset...")
            transport.resetDevice()

            localCamera = SonyPtpCamera(transport)

            if (!localCamera.openSession()) {
                _connectionState.value = CameraConnectionState.Error(
                    "Can't talk to the camera. Unplug the USB cable, wait a moment, then plug it back in."
                )
                return@withContext
            }

            if (!localCamera.getDeviceInfo()) {
                Log.w(TAG, "Could not get device info, continuing anyway")
            }

            // Initialize Sony vendor extension (required for Sony-specific commands)
            localCamera.initSonyExtension()

            // Commit: take ownership of the resources.
            usbDevice = device
            usbConnection = localConn
            ptpInterface = localIface
            ptpCamera = localCamera
            committed = true

            _cameraName.value = localCamera.deviceName ?: "Sony Camera (USB)"
            _connectionState.value = CameraConnectionState.Ready

            Log.d(TAG, "USB camera connected: ${localCamera.deviceName}")

            // Pre-warm the shutter pipeline BEFORE starting liveview. The
            // first SetControlDeviceB(SHUTTER) on a fresh PC-Remote session
            // takes ~8s for the camera firmware to context-switch into
            // capture-handling mode. Doing it here (inside the connection
            // flow the user is already waiting on) means the user's first
            // real shot uses the fast path. Done before liveview so the
            // pre-warm itself isn't fighting liveview for the PTP lock.
            localCamera.prewarmShutter()

            // Auto-start liveview — camera is already in PC Remote mode
            // with liveview active after SDIO init
            Log.d(TAG, "Auto-starting USB liveview...")
            startLiveview()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // Caller (disconnect / detach) is tearing us down. Don't flip to Error —
            // let the cleanup path already in flight set the authoritative state.
            Log.d(TAG, "Connect attempt cancelled")
            throw cancel
        } catch (e: Exception) {
            Log.e(TAG, "USB connection error", e)
            _connectionState.value = CameraConnectionState.Error(
                "Couldn't connect to the camera. Unplug and replug the USB cable, then try again."
            )
        } finally {
            // Release anything we opened if we didn't fully commit. Safe to call
            // on null refs / already-closed handles — each wrapped in try/catch.
            if (!committed) {
                try { localCamera?.closeSession() } catch (e: Exception) { Log.w(TAG, "closeSession rollback: ${e.message}") }
                if (ifaceClaimed && localIface != null && localConn != null) {
                    try { localConn.releaseInterface(localIface) } catch (e: Exception) { Log.w(TAG, "releaseInterface rollback: ${e.message}") }
                }
                try { localConn?.close() } catch (e: Exception) { Log.w(TAG, "connection close rollback: ${e.message}") }
            }
        }
    }

    private fun hasPtpInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // PTP/MTP uses class 6 (Still Image), but some devices use class 255 (vendor-specific)
            if (iface.interfaceClass == PtpConstants.USB_CLASS_PTP ||
                iface.interfaceClass == 255) return true
        }
        return false
    }

    private fun findPtpInterface(device: UsbDevice): UsbInterface? {
        // Prefer class 6 (standard PTP/MTP)
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == PtpConstants.USB_CLASS_PTP) return iface
        }
        // Fallback: first interface with bulk endpoints (vendor-specific PTP)
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var hasBulkIn = false
            var hasBulkOut = false
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) hasBulkIn = true
                    if (ep.direction == UsbConstants.USB_DIR_OUT) hasBulkOut = true
                }
            }
            if (hasBulkIn && hasBulkOut) return iface
        }
        return null
    }
}
