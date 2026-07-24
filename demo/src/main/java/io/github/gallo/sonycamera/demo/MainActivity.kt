package io.github.gallo.sonycamera.demo

import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import io.github.gallo.sonycamera.service.CameraConnectionClient

/**
 * Single-Activity demo host.
 *
 * Two responsibilities beyond drawing the UI:
 * 1. Forward `USB_DEVICE_ATTACHED` intents to the [CameraConnectionClient].
 *    This intent is delivered ONLY to Activities via the manifest
 *    intent-filter — not to runtime receivers — so this is the only way the
 *    library learns a camera was plugged in while the app is foreground.
 * 2. Ask for POST_NOTIFICATIONS (Android 13+) so the foreground-service
 *    notification is visible.
 */
class MainActivity : ComponentActivity() {

    private val camera: CameraConnectionClient
        get() = (application as DemoApp).camera

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        maybeRequestNotificationPermission()
        forwardUsbAttachIntent(intent)

        setContent {
            CameraScreen(camera = camera)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // With launchMode=singleTop, Android redelivers USB_DEVICE_ATTACHED
        // here instead of recreating the Activity.
        forwardUsbAttachIntent(intent)
    }

    private fun forwardUsbAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) ?: return
        camera.onUsbDeviceAttached(device)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
