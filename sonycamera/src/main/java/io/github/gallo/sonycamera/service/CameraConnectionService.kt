package io.github.gallo.sonycamera.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.gallo.sonycamera.CameraConnectionState
import io.github.gallo.sonycamera.SonyCamera
import io.github.gallo.sonycamera.usb.UsbCameraConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the USB camera connection.
 *
 * This service is the single owner of the connection lifecycle. It holds the
 * [UsbCameraConnectionManager] engine (USB handles, PTP session, liveview
 * loop, state flows) and exposes it to the rest of the app through a [Binder].
 *
 * Lifecycle:
 * - It is *bound* (BIND_AUTO_CREATE) by [CameraConnectionClient] for the
 *   lifetime of the app process, which is how the UI observes state.
 * - It is additionally *started* (startForegroundService) whenever a camera
 *   connection is requested. While started it runs as a foreground service,
 *   so the connection survives the Activity being swiped away.
 * - On explicit disconnect it drops foreground and stops itself.
 *
 * Uptime levers implemented here:
 * 1. Self-recovery — START_STICKY: if Android kills the process while a
 *    camera is connected, the service is recreated and reconnects.
 * 2. Watchdog — a heartbeat coroutine that restarts a stalled liveview.
 *    Initialization errors remain stable until an explicit reconnect, so a
 *    blocked synchronous USB read cannot overlap an automatic retry.
 *
 * The notification's icon and copy come from [SonyCamera.notificationConfig].
 */
class CameraConnectionService : Service() {

    companion object {
        private const val TAG = "CameraConnService"
        const val CHANNEL_ID = "sonycamera_connection"
        const val NOTIFICATION_ID = 1001

        private const val ACTION_CONNECT = "io.github.gallo.sonycamera.action.CONNECT"

        // Watchdog tuning.
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        // If the camera is Ready but no liveview frame has arrived in this
        // long, the liveview loop is stalled — restart it (cheap, no USB reset).
        private const val LIVEVIEW_STALL_MS = 10_000L
        /** Intent that starts the service in the foreground and connects. */
        fun connectIntent(context: Context): Intent =
            Intent(context, CameraConnectionService::class.java).setAction(ACTION_CONNECT)

        /**
         * Register the notification channel. Called once from
         * [CameraConnectionClient] before any foreground service is started.
         */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val config = SonyCamera.notificationConfig
            val channel = NotificationChannel(
                CHANNEL_ID,
                config.channelName,
                // IMPORTANCE_LOW: no sound / vibrate; discreet in kiosk use.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = config.channelDescription
                setShowBadge(false)
                setSound(null, null)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /**
     * Local binder. Same-process only — exposes the engine's flows and
     * commands directly to [CameraConnectionClient].
     */
    inner class CameraBinder : Binder() {
        val connectionState get() = engine.connectionState
        val cameraName get() = engine.cameraName
        val events get() = engine.events
        val liveviewFrames get() = engine.liveviewFrames

        /** Whether a Sony PTP camera is currently plugged into USB. */
        fun hasCameraAttached(): Boolean = engine.findSonyCamera() != null

        fun requestConnection() = this@CameraConnectionService.handleConnectRequest()
        fun onUsbDeviceAttached(device: android.hardware.usb.UsbDevice) =
            engine.onUsbDeviceAttached(device)

        suspend fun startLiveview() = engine.startLiveview()
        suspend fun stopLiveview() = engine.stopLiveview()
        suspend fun takePhoto() = engine.takePhoto()
        fun isReady() = engine.isReady()
        fun disconnect() = this@CameraConnectionService.handleDisconnect()
    }

    private lateinit var engine: UsbCameraConnectionManager
    private val binder = CameraBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Elapsed-realtime of the last liveview frame; 0 until the first frame. */
    @Volatile private var lastFrameTime = 0L
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        engine = UsbCameraConnectionManager(
            context = applicationContext,
            onUsbPermissionGranted = { handleUsbPermissionGranted() }
        )

        // Drive foreground lifecycle + notification from the connection state.
        scope.launch {
            engine.connectionState.collect { state -> onConnectionStateChanged(state) }
        }
        // Track frame arrival for the watchdog's stall detection.
        scope.launch {
            engine.liveviewFrames.collect { lastFrameTime = SystemClock.elapsedRealtime() }
        }
        startWatchdog()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // We may have been launched via startForegroundService (either by the
        // client on a connect request, or by the system on a sticky / crash
        // restart). On Android 14+, startForeground(type=connectedDevice)
        // throws SecurityException unless we currently have a qualifier from
        // the allowed list — for us, an attached USB camera. If the cable is
        // unplugged when this fires (very common for sticky restarts), going
        // foreground would crash the process and put us in a crash-loop.
        // Guard the foreground promotion on actually having a camera attached.
        val camera = engine.findSonyCamera()
        if (camera == null) {
            Log.d(TAG, "onStartCommand with no camera attached — skipping " +
                    "foreground and stopping. Will be re-started on USB attach.")
            // Do NOT call startForeground here — it would SecurityException.
            // The service remains bound (BIND_AUTO_CREATE keeps it alive),
            // but the started-state goes away so the system won't keep
            // restarting it. START_NOT_STICKY ensures no further sticky
            // restart is scheduled for this start id.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Android 14+ validates the connectedDevice runtime prerequisite when
        // startForeground() runs. Merely seeing the device is insufficient:
        // UsbManager.requestPermission() must already have completed.
        if (!engine.hasUsbPermission(camera)) {
            Log.w(TAG, "Foreground start requested before USB permission was granted — stopping")
            engine.reportConnectionError(
                "USB access is required. Reconnect the camera and allow access."
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!tryEnterForeground(engine.connectionState.value)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_CONNECT -> {
                Log.d(TAG, "Connect requested")
                engine.connectToCamera()
            }
            // A null intent means START_STICKY recreated us after the process
            // was killed while a camera session was active — reconnect.
            null -> {
                Log.d(TAG, "Sticky restart — attempting to reconnect the camera")
                engine.connectToCamera()
            }
        }

        // START_STICKY: if the system kills us while connected, recreate the
        // service so the watchdog and reconnect logic come back automatically.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        engine.destroy()
        scope.cancel()
        super.onDestroy()
    }

    // ── Foreground lifecycle ────────────────────────────────────────────────

    private fun onConnectionStateChanged(state: CameraConnectionState) {
        if (state is CameraConnectionState.Disconnected) {
            // A connection that ended (user disconnect, or grace window
            // expiry). If we were running foreground, there is nothing left
            // to keep alive — drop foreground and let the service stop.
            if (isForeground) {
                Log.d(TAG, "Connection ended — leaving foreground")
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForeground = false
                stopSelf()
            }
        } else if (isForeground) {
            updateNotification(state)
        }
    }

    private fun enterForeground(state: CameraConnectionState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(state),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(state))
        }
        isForeground = true
    }

    /**
     * Prevent platform/OEM foreground-service policy failures from crashing
     * the entire app process.
     */
    private fun tryEnterForeground(state: CameraConnectionState): Boolean {
        return try {
            enterForeground(state)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Connected-device foreground prerequisites not satisfied", e)
            engine.reportConnectionError(
                "Android did not allow the camera service. Reconnect the camera and allow USB access."
            )
            false
        } catch (e: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException on Android 12+.
            Log.e(TAG, "Foreground service start is not allowed in the current app state", e)
            engine.reportConnectionError(
                "Open the app and connect the camera again."
            )
            false
        }
    }

    private fun updateNotification(state: CameraConnectionState) {
        ContextCompat.getSystemService(this, NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: CameraConnectionState) =
        SonyCamera.notificationConfig.let { config ->
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(config.smallIcon)
                .setContentTitle(config.title)
                .setContentText(
                    when (state) {
                        is CameraConnectionState.Ready -> config.connectedText
                        is CameraConnectionState.Connecting,
                        is CameraConnectionState.Initializing,
                        is CameraConnectionState.Scanning -> config.connectingText
                        is CameraConnectionState.Error -> config.errorText
                        is CameraConnectionState.Disconnected -> config.startingText
                    }
                )
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }

    private fun handleDisconnect() {
        Log.d(TAG, "Disconnect requested")
        // engine.disconnect() flips state to Disconnected, which the state
        // collector turns into stopForeground + stopSelf.
        engine.disconnect()
    }

    /**
     * Entry point for user/attach-driven connection requests. USB permission
     * is acquired while this service is only bound; foreground promotion is
     * deliberately deferred until Android reports that permission was granted.
     */
    private fun handleConnectRequest() {
        val camera = engine.findSonyCamera()
        if (camera == null) {
            engine.connectToCamera()
            return
        }

        if (engine.hasUsbPermission(camera)) {
            startForegroundConnection()
        } else {
            Log.d(TAG, "Requesting USB permission before foreground promotion")
            engine.requestUsbPermission(camera)
        }
    }

    /** Continue the connection only after UsbManager confirms access. */
    private fun handleUsbPermissionGranted() {
        Log.d(TAG, "USB permission ready — starting connected-device foreground service")
        startForegroundConnection()
    }

    private fun startForegroundConnection() {
        try {
            ContextCompat.startForegroundService(this, connectIntent(this))
        } catch (e: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException on Android 12+.
            Log.e(TAG, "Unable to start camera foreground service", e)
            engine.reportConnectionError(
                "Open the app and connect the camera again."
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to start camera foreground service", e)
            engine.reportConnectionError(
                "Android did not allow USB camera access."
            )
        }
    }

    // ── Watchdog (uptime lever 2) ────────────────────────────────────────────

    private fun startWatchdog() {
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                runWatchdogCheck()
            }
        }
    }

    private suspend fun runWatchdogCheck() {
        when (engine.connectionState.value) {
            is CameraConnectionState.Ready -> {
                if (engine.isCapturing()) {
                    // Liveview is deliberately stopped while shutter and
                    // photo-transfer commands own the PTP link. Treat this as
                    // healthy and move the watchdog baseline forward; forcing
                    // a restart here would interleave GetObject with 0x9207.
                    lastFrameTime = SystemClock.elapsedRealtime()
                    return
                }
                val sinceFrame = SystemClock.elapsedRealtime() - lastFrameTime
                // Only act once we've seen at least one frame — the engine's
                // own liveview loop handles the never-got-a-frame case.
                if (lastFrameTime != 0L && sinceFrame > LIVEVIEW_STALL_MS) {
                    Log.w(TAG, "Liveview stalled (${sinceFrame}ms since last frame) — restarting it")
                    engine.stopLiveview()
                    engine.startLiveview()
                    // Give the restarted loop a fresh grace window.
                    lastFrameTime = SystemClock.elapsedRealtime()
                }
            }
            // Keep initialization failures stable. The user can explicitly
            // reconnect after fixing USB/camera state, but the watchdog must
            // never start a second PTP handshake behind a blocked first one.
            is CameraConnectionState.Error,
            is CameraConnectionState.Disconnected -> Unit
            // Connecting / Initializing / Scanning — a connection attempt is
            // already in flight; leave it alone.
            else -> Unit
        }
    }
}
