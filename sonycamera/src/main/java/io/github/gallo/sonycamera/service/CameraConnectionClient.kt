package io.github.gallo.sonycamera.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.gallo.sonycamera.CameraConnectionManager
import io.github.gallo.sonycamera.CameraConnectionState
import io.github.gallo.sonycamera.CameraEvent
import io.github.gallo.sonycamera.CameraOperationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * The main entry point of the library.
 *
 * App-side handle to the USB camera connection, which is owned by
 * [CameraConnectionService]. It binds to the service for the lifetime of the
 * process and proxies the service's state flows and commands, so callers get
 * the [CameraConnectionManager] surface without dealing with [ServiceConnection]
 * boilerplate themselves.
 *
 * Create exactly **one** instance per process and share it (it binds a service
 * and registers a USB receiver). With a DI framework, bind it as a singleton;
 * without one, hold it on your `Application`:
 *
 * ```
 * class MyApp : Application() {
 *     val camera by lazy { CameraConnectionClient(this) }
 * }
 * ```
 *
 * Binding is done with the application context, so it persists across Activity
 * recreation. The service only runs in the *foreground* while a camera
 * connection is active (started via [connectToCamera]); the rest of the time
 * it is merely bound.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraConnectionClient(
    context: Context
) : CameraConnectionManager {

    // Always hold the application context — this object outlives any Activity.
    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "CameraConnClient"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The bound service's binder, or null until [onServiceConnected]. */
    private val binderFlow = MutableStateFlow<CameraConnectionService.CameraBinder?>(null)

    /** A USB attach event that arrived before the binder was ready. */
    private var pendingAttach: UsbDevice? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Bound to CameraConnectionService")
            val cameraBinder = service as? CameraConnectionService.CameraBinder
            binderFlow.value = cameraBinder
            val attach = pendingAttach
            pendingAttach = null
            if (cameraBinder == null) return

            // Auto-connect on startup. If the process survived a swipe-away
            // the camera is already connected and this is a no-op. If the
            // process was killed and recreated, this reconnects without the
            // user having to tap Connect again. Also covers a USB attach
            // intent that arrived before the service binding landed.
            val alreadyConnected =
                cameraBinder.connectionState.value !is CameraConnectionState.Disconnected
            when {
                !alreadyConnected && (attach != null || cameraBinder.hasCameraAttached()) -> {
                    Log.d(TAG, "Startup auto-connect: camera attached, connecting")
                    connectToCamera()
                }
                attach != null -> cameraBinder.onUsbDeviceAttached(attach)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "CameraConnectionService disconnected")
            binderFlow.value = null
        }
    }

    init {
        // Register the foreground-service notification channel up front so the
        // service can promote itself to foreground the instant a camera connects.
        CameraConnectionService.createNotificationChannel(this.context)

        // BIND_AUTO_CREATE creates the service (so the engine + watchdog are
        // running) without making it a foreground/started service.
        val bound = this.context.bindService(
            Intent(this.context, CameraConnectionService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        if (!bound) Log.e(TAG, "Failed to bind CameraConnectionService")
    }

    // ── CameraConnectionManager: state proxied from the service ─────────────

    override val connectionState: StateFlow<CameraConnectionState> =
        binderFlow
            .flatMapLatest { it?.connectionState ?: flowOf(CameraConnectionState.Disconnected) }
            .stateIn(scope, SharingStarted.Eagerly, CameraConnectionState.Disconnected)

    override val cameraName: StateFlow<String?> =
        binderFlow
            .flatMapLatest { it?.cameraName ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override val events: SharedFlow<CameraEvent> =
        binderFlow
            .flatMapLatest { it?.events ?: emptyFlow() }
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    override val liveviewFrames: SharedFlow<Bitmap> =
        binderFlow
            .flatMapLatest { it?.liveviewFrames ?: emptyFlow() }
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    // ── Commands ────────────────────────────────────────────────────────────

    /**
     * Connect to a Sony camera over USB. Starts the service in the foreground
     * so the connection survives the app being swiped away. Works even if the
     * binder has not landed yet — the service handles the intent on its own.
     */
    fun connectToCamera() {
        ContextCompat.startForegroundService(
            context,
            CameraConnectionService.connectIntent(context)
        )
    }

    override fun disconnect() {
        binderFlow.value?.disconnect()
    }

    override suspend fun startLiveview(): CameraOperationResult =
        binderFlow.value?.startLiveview()
            ?: CameraOperationResult.Failure("Camera service not ready")

    override suspend fun stopLiveview(): CameraOperationResult =
        binderFlow.value?.stopLiveview()
            ?: CameraOperationResult.Failure("Camera service not ready")

    override suspend fun takePhoto(): CameraOperationResult =
        binderFlow.value?.takePhoto()
            ?: CameraOperationResult.Failure("Camera not connected")

    override fun isReady(): Boolean = binderFlow.value?.isReady() == true

    /**
     * Handle a USB attach event (from your Activity's manifest intent-filter).
     *
     * If we are idle, this auto-connects to the camera the user just plugged
     * in. If a session is already active, it is a mid-session reattach and is
     * handed to the engine's grace-window reconnect logic. If the service
     * binder is not ready yet, the event is held and replayed once it lands.
     */
    fun onUsbDeviceAttached(device: UsbDevice) {
        val binder = binderFlow.value
        if (binder == null) {
            pendingAttach = device
            return
        }
        if (binder.connectionState.value is CameraConnectionState.Disconnected) {
            Log.d(TAG, "Camera attached while idle — auto-connecting")
            connectToCamera()
        } else {
            binder.onUsbDeviceAttached(device)
        }
    }
}
