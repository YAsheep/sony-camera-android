package io.github.gallo.sonycamera

import androidx.annotation.DrawableRes

/**
 * Library-wide configuration entry point.
 *
 * The foreground service that keeps the USB session alive needs a notification
 * (Android requires one for any foreground service). Set [notificationConfig]
 * before the first connection if you want to brand that notification with your
 * own icon and copy; otherwise a neutral default is used.
 *
 * ```
 * SonyCamera.notificationConfig = CameraNotificationConfig(
 *     smallIcon = R.drawable.ic_my_app,
 *     title = "My App",
 *     connectedText = "Camera ready",
 * )
 * ```
 */
object SonyCamera {

    /** Appearance of the foreground-service notification. */
    @Volatile
    var notificationConfig: CameraNotificationConfig = CameraNotificationConfig()
}

/**
 * Controls how the camera-connection foreground-service notification looks.
 *
 * All fields have neutral defaults so the library works with zero configuration.
 * Override [smallIcon] with one of your own app's drawables to avoid shipping
 * the library's generic camera glyph in your status bar.
 */
data class CameraNotificationConfig(
    /** Status-bar icon. Must be a small, single-color drawable. */
    @DrawableRes val smallIcon: Int = R.drawable.ic_sonycamera_notification,
    /** Notification title (usually your app name). */
    val title: String = "Camera",
    /** Shown while a camera is connected and ready. */
    val connectedText: String = "Camera connected",
    /** Shown while connecting/initializing. */
    val connectingText: String = "Connecting to camera…",
    /** Shown when the connection has errored. */
    val errorText: String = "Camera connection problem",
    /** Shown briefly before the first state arrives. */
    val startingText: String = "Starting camera…",
    /** Android notification channel name (Settings > Notifications). */
    val channelName: String = "Camera connection",
    /** Android notification channel description. */
    val channelDescription: String = "Keeps the USB camera session alive.",
)
