# sony-camera-android

Control a Sony Alpha camera from Android over a **wired USB** connection — live
view, autofocus, and full-resolution capture - using PTP (Picture Transfer
Protocol) with Sony's vendor extensions. No WiFi, no Sony SDK, no cloud.

This is the camera-control engine extracted from a production photobooth app
that ran unattended at events. The connection layer is built for reliability:
it survives the app being swiped away, auto-reconnects a bumped cable, and
recovers from the firmware quirks that make Sony USB control finicky.

> **Status:** extracted and building as a standalone library + demo. The PTP
> stack is battle-tested on the **Sony α6600 (ILCE-6600)**. Other Alpha bodies
> that speak the same PC-Remote PTP dialect are likely to work but are untested,
> reports welcome.

---

## Features

- 🔌 **Wired USB PTP** - talks directly to the camera via Android's USB Host API.
- 📺 **Live view streaming** - JPEG frames decoded to `Bitmap` at ~15–30 fps.
- 📸 **Full-resolution capture** — fires the shutter and downloads the JPEG off
  the camera, with ret/queue handling for Sony's transfer quirks.
- ♻️ **Resilient connection** — a foreground service keeps the session alive in
  the background; a watchdog restarts stalled live view and retries failed
  connects; a grace window silently reconnects a briefly-unplugged cable.
- 🧊 **Clean Kotlin API** — coroutine `suspend` functions and `StateFlow` /
  `SharedFlow` state. No DI framework required.

## Requirements

- An Android device with **USB Host** support (most phones/tablets; OTG cable or
  USB-C–to–USB-C).
- A Sony Alpha camera set to **USB → PC Remote** (or **Auto**) in its menu.
- `compileSdk` 35, `minSdk` 26, Kotlin, Java 17.

## Modules

| Module        | What it is                                                        |
|---------------|-------------------------------------------------------------------|
| `:sonycamera` | The library (Android AAR). All the PTP/USB/service code.          |
| `:demo`       | A minimal Compose app: connect → live view → tap to capture.      |

## Quick start

### 1. Add the dependency

Until a published artifact exists, include the module directly (Git submodule or
copy), then in `settings.gradle.kts`:

```kotlin
include(":sonycamera")
```

and in your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":sonycamera"))
}
```

The library's manifest contributes the foreground service, the USB-host feature,
and the foreground-service permissions automatically via manifest merging — you
don't copy those into your own manifest.

### 2. Hold one client for the whole process

The client binds a service and registers a USB receiver, so create exactly one
and share it. The simplest place is your `Application`:

```kotlin
class MyApp : Application() {
    val camera by lazy { CameraConnectionClient(this) }

    override fun onCreate() {
        super.onCreate()
        // Optional: brand the foreground-service notification.
        SonyCamera.notificationConfig = CameraNotificationConfig(
            smallIcon = R.drawable.ic_my_notification,
            title = "My App",
        )
    }
}
```

With Hilt/Koin, bind `CameraConnectionClient` as a singleton instead.

### 3. Forward USB attach intents from your launcher Activity

`USB_DEVICE_ATTACHED` is delivered only to Activities (via the manifest
intent-filter), never to runtime receivers — so this hand-off is required for
plug-in detection and seamless reconnect:

```kotlin
class MainActivity : ComponentActivity() {
    private val camera get() = (application as MyApp).camera

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardUsb(intent)
        // …setContent { … }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); forwardUsb(intent)
    }

    private fun forwardUsb(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            ?.let(camera::onUsbDeviceAttached)
    }
}
```

Add the intent-filter (and reuse the library's device filter) to that Activity:

```xml
<activity android:name=".MainActivity" android:launchMode="singleTop">
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data
        android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
        android:resource="@xml/sony_usb_device_filter" />
</activity>
```

### 4. Connect, observe, capture

```kotlin
// Kick off a connection (also auto-connects when a camera is plugged in).
camera.connectToCamera()

// Observe state.
lifecycleScope.launch {
    camera.connectionState.collect { state -> /* Disconnected, Connecting, Ready, Error… */ }
}

// Render live view.
lifecycleScope.launch {
    camera.liveviewFrames.collect { bitmap -> imageView.setImageBitmap(bitmap) }
}

// One-shot events.
lifecycleScope.launch {
    camera.events.collect { event ->
        when (event) {
            is CameraEvent.PhotoCaptured -> showPhoto(event.bitmap)
            is CameraEvent.ShutterFired  -> playShutterFlash()
            is CameraEvent.ConnectionLost -> /* … */
            is CameraEvent.Error -> /* … */
        }
    }
}

// Take a photo (suspends until the full-res JPEG is downloaded or it fails).
lifecycleScope.launch {
    when (val r = camera.takePhoto()) {
        is CameraOperationResult.Success -> { /* arrives via CameraEvent.PhotoCaptured */ }
        is CameraOperationResult.Failure -> toast(r.message)
        else -> {}
    }
}
```

## Public API

The whole surface is small. `CameraConnectionClient` implements
`CameraConnectionManager`:

| Member                              | Type                          | Purpose                              |
|-------------------------------------|-------------------------------|--------------------------------------|
| `connectionState`                   | `StateFlow<CameraConnectionState>` | Disconnected / Connecting / Initializing / Ready / Error |
| `cameraName`                        | `StateFlow<String?>`          | e.g. `"Sony ILCE-6600"`              |
| `liveviewFrames`                    | `SharedFlow<Bitmap>`          | Decoded live-view frames             |
| `events`                            | `SharedFlow<CameraEvent>`     | Capture / shutter / lost / error     |
| `connectToCamera()`                 | `fun`                         | Start (or retry) a connection        |
| `takePhoto()`                       | `suspend`                     | Fire shutter + download JPEG         |
| `startLiveview()` / `stopLiveview()`| `suspend`                     | Live view is auto-started on connect |
| `disconnect()`                      | `fun`                         | End the session, release the camera  |
| `isReady()`                         | `fun`                         | Connected and ready                  |

## How it works

```
CameraConnectionClient   ← your app talks to this (binds the service)
        │  binds
CameraConnectionService  ← foreground service: owns lifecycle + watchdog
        │  owns
UsbCameraConnectionManager ← USB host: device/permission/reconnect, state flows
        │  drives
SonyPtpCamera            ← Sony PC-Remote ops: SDIO init, shutter, live view, download
        │  over
PtpTransport             ← raw PTP containers over USB bulk transfers
```

A few hard-won details encoded here:

- **Live view** is `GetObject(0xFFFFC002)` — a Sony magic handle that returns a
  JPEG frame per call.
- **Shutter pre-warm**: the first `SetControlDeviceB(shutter)` on a fresh
  session takes ~8 s while the firmware context-switches; the library pays that
  cost at connect so the user's first real shot is fast.
- **Graceful teardown** releases Sony's "host has priority" flag and closes the
  session so the camera returns to normal — done on a scope that outlives
  cancellation so it actually reaches the camera.
- **Reconnect grace window**: a physical detach holds the UI in *Connecting* and
  polls for a re-plug for several seconds before giving up.

Each of these is documented inline in the source with the symptom it addresses.

## Camera setup

On the camera: **Menu → USB → USB Connection → PC Remote** (some bodies:
*Auto*). On first plug-in, Android shows a USB permission dialog — tap **OK**
(optionally "always for this device"). If capture/live view never starts, the
camera is usually in the wrong USB mode or another app (MTP/Photos) grabbed the
interface — unplug, close other photo apps, replug.

## Credits

- Sony PTP vendor opcodes and the live-view handle were reverse-engineered with
  reference to [**libgphoto2**](http://www.gphoto.org/) and the
  [**Sony Camera Remote SDK**](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)
  protocol behavior.
- BLE-era inspiration and Sony Alpha protocol notes from
  [**alpharemote**](https://github.com/Staacks/alpharemote) by Sebastian Staacks.

## License

[MIT](LICENSE) © 2026 Andrew Gallo. Not affiliated with or endorsed by Sony.
