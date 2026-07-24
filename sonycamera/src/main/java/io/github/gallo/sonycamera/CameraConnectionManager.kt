package io.github.gallo.sonycamera

import android.graphics.Bitmap
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Camera connection state.
 */
sealed class CameraConnectionState {
    data object Disconnected : CameraConnectionState()
    data object Scanning : CameraConnectionState()
    data object Connecting : CameraConnectionState()
    data object Initializing : CameraConnectionState()
    data object Ready : CameraConnectionState()
    data class Error(val message: String) : CameraConnectionState()
}

/**
 * Events emitted by the camera connection.
 */
sealed class CameraEvent {
    data class PhotoCaptured(val bitmap: Bitmap) : CameraEvent()
    data class Error(val message: String) : CameraEvent()
    data object ConnectionLost : CameraEvent()

    /**
     * The shutter is firing now — the capture sequence has just begun.
     * Emitted so the UI flash/sound coincide with the real capture instead
     * of leading it. The live preview was running right up to this instant.
     */
    data object ShutterFired : CameraEvent()
}

/**
 * Result of a camera operation.
 */
sealed class CameraOperationResult {
    data object Success : CameraOperationResult()
    data class SuccessWithData<T>(val data: T) : CameraOperationResult()
    data class Failure(val message: String) : CameraOperationResult()
}

/**
 * Interface for camera connections. Implemented by the USB (PTP protocol) transport.
 */
interface CameraConnectionManager {

    /** Current connection state. */
    val connectionState: StateFlow<CameraConnectionState>

    /** Name of the connected camera (e.g., "Sony ILCE-6600"). */
    val cameraName: StateFlow<String?>

    /** Camera events (photo captured, errors, connection lost). */
    val events: SharedFlow<CameraEvent>

    /** Liveview frames delivered as Bitmaps over USB PTP. */
    val liveviewFrames: SharedFlow<Bitmap>

    /** Start liveview streaming. */
    suspend fun startLiveview(): CameraOperationResult

    /** Stop liveview streaming. */
    suspend fun stopLiveview(): CameraOperationResult

    /** Take a photo and return the captured bitmap. */
    suspend fun takePhoto(): CameraOperationResult

    /** Disconnect from the camera. */
    fun disconnect()

    /** Whether the camera is connected and ready. */
    fun isReady(): Boolean
}
