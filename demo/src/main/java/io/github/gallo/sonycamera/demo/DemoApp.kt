package io.github.gallo.sonycamera.demo

import android.app.Application
import io.github.gallo.sonycamera.CameraNotificationConfig
import io.github.gallo.sonycamera.SonyCamera
import io.github.gallo.sonycamera.service.CameraConnectionClient

/**
 * Holds the single, process-wide [CameraConnectionClient]. The library binds a
 * foreground service and registers a USB receiver, so there must be exactly one
 * instance — keeping it on the [Application] is the simplest way to do that
 * without a DI framework.
 */
class DemoApp : Application() {

    /** Created lazily on first access (when the UI first observes the camera). */
    val camera: CameraConnectionClient by lazy { CameraConnectionClient(this) }

    override fun onCreate() {
        super.onCreate()
        // Brand the foreground-service notification. Entirely optional.
        SonyCamera.notificationConfig = CameraNotificationConfig(
            title = "Sony Camera Demo",
            connectedText = "Camera connected",
            connectingText = "Connecting…",
        )
    }
}
