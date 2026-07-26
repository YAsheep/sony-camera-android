# sony-camera-android

Control a Sony Alpha camera from Android over a **wired USB** connection — live
view, autofocus, and full-resolution capture - using PTP (Picture Transfer
Protocol) with Sony's vendor extensions. No WiFi, no Sony SDK, no cloud.

This is the camera-control engine extracted from a production photobooth app
that ran unattended at events. The connection layer is built for reliability:
it survives the app being swiped away, auto-reconnects a bumped cable, and
recovers from the firmware and Android USB-host quirks that make Sony USB
control finicky.

> **Status:** extracted and building as a standalone library + demo. The PTP
> stack has been exercised with Sony Alpha bodies using the PC-Remote PTP
> dialect, including the **α6600 (ILCE-6600)** and the **ILCE-7C** USB profile.
> Other Alpha bodies may require a model-specific SDIO handshake; reports are
> welcome.

---

## Features

- 🔌 **Wired USB PTP** - talks directly to the camera via Android's USB Host API.
- 📺 **Live view streaming** - polls Sony's live-view object and decodes JPEG
  frames to `Bitmap` at roughly 15–30 fps, with bounded stall recovery.
- 📸 **Full-resolution capture** — stops and joins live view before sending one
  shutter sequence, downloads the JPEG, then restarts live view exactly once.
- 🧭 **Standard-first initialization** — performs `OpenSession (0x1002)` and
  `GetDeviceInfo (0x1001)` before Sony's `0x9201`/`0x9202`/`0x9205` setup.
- ♻️ **Resilient connection** — a foreground service keeps the session alive in
  the background; a watchdog restarts stalled live view and a grace window
  silently reconnects a briefly-unplugged cable.
- 🛡️ **Explicit failures** — initialization errors remain `Error`; the service
  never publishes `Ready` until the Sony handshake and live view both succeed.
- 🧊 **Clean Kotlin API** — coroutine `suspend` functions and `StateFlow` /
  `SharedFlow` state. No DI framework required.

## Requirements

- An Android device with **USB Host** support (most phones/tablets; OTG cable or
  USB-C–to–USB-C).
- A Sony Alpha camera set to **USB → PC Remote** (or **Auto**) in its menu.
- `compileSdk` 35, `minSdk` 26, Kotlin, Java 17.
- Android 14+ may require the app to allow notifications so the connected-device
  foreground-service notification is visible.

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
// A second call while a capture is active is rejected; it never fires a second
// exposure.
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

`takePhoto()` returns `Success` only after a usable full-resolution JPEG has
been downloaded. If the camera exposed but the transfer failed, it returns a
failure explaining that the photo was taken but its preview was unavailable;
the library does not retry by firing the shutter again.

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
- **PTP container framing**: Bulk IN data may contain split or coalesced PTP
  containers. The transport carries incomplete bytes across reads and consumes
  the final short packet/ZLP without treating it as a new frame.
- **USB transfer fairness**: all Bulk and Interrupt operations share a fair
  serialized transport lock. Bulk transfers are chunked to 16 KiB for Android
  USB-host implementations that fail larger writes.
- **Sony initialization**: a normal connection does not start with
  `DeviceReset`. Reset is reserved for recovery after a failed `OpenSession`,
  avoiding a race where the camera is still resetting when `GetDeviceInfo`
  arrives.
- **Capture isolation**: the event pump and live-view loop pause while shutter
  commands and the photo download own the PTP link. The shutter uses
  `0x9207`: half-press (`0xD2C1=2`), full-press (`0xD2C2=2`), then releases both
  values with `1`.
- **No startup shutter pre-warm**: connecting does not half-press the shutter,
  so it does not intentionally trigger the camera's AF-assist/red light.
- **Graceful teardown** releases Sony's "host has priority" flag and closes the
  session so the camera returns to normal — done on a scope that outlives
  cancellation so it actually reaches the camera.
- **Reconnect grace window**: a physical detach holds the UI in *Connecting* and
  polls for a re-plug for several seconds before giving up.

Each of these is documented inline in the source with the symptom it addresses.

### PTP connection sequence

After USB permission is granted, the library claims the PTP interface and
requires Bulk OUT, Bulk IN, and Interrupt IN endpoints. It then performs this
sequence; a failed step sets `CameraConnectionState.Error` and aborts before
`Ready`:

```text
USB_DEVICE_ATTACHED → USB permission → claimInterface/endpoints
  → start Interrupt IN listener (held until remote mode is ready)
  → OpenSession              (0x1002)
  → GetDeviceInfo             (0x1001)
  → Sony SDIO Connect phase  (0x9201..., param 1)
  → acquire USB priority     (0x9205, property 0xD25A = 1)
  → start LiveView            (GetObject 0xFFFFC002)
  → Ready
```

The USB filter matches Sony's vendor ID `0x054C` (1356) and PTP interface
class/subclass/protocol `6/1/1`; it intentionally does not hard-code one
product ID. For example, the ILCE-7C profile observed in logs is product ID
`0x0D2B`.

## Camera setup

On the camera: **Menu → USB → USB Connection → PC Remote** (some bodies:
*Auto*). On first plug-in, Android shows a USB permission dialog — tap **OK**
(optionally "always for this device"). The app must receive
`USB_DEVICE_ATTACHED` in its launcher Activity; the library's runtime receiver
cannot receive that attach broadcast by itself. Forward the device to
`CameraConnectionClient.onUsbDeviceAttached()` as shown above.

The library filter accepts Sony vendor ID `0x054C` with a PTP interface. It does
not request camera, microphone, network, or storage access: the only user-facing
authorization is Android's USB device permission dialog (plus the normal
notification permission on Android 13+ if the app requests it).

If capture/live view never starts, check the camera USB mode, close other photo
apps that may have claimed MTP/PTP, unplug, wait a moment, and replug.

## Troubleshooting

### Stuck at “Waiting for live view”

Check logcat for the ordered `PTP step` messages. `Ready` is published only
after the first live-view startup succeeds. A live view that never produces a
frame for 18 seconds changes to `Error` and asks for a physical unplug/replug;
this is required for camera firmware states that survive an app reconnect.

### `0x9205` or `0x9207` fails

These commands are sent only after `0x1002` → `0x1001` and Sony SDIO setup. For
`0x9205`, verify the log reached `GetAllDevicePropData (0x9209)` and that the
camera accepted USB priority property `0xD25A=1`. For `0x9207`, capture pauses
live view and the event listener, then sends the half/full press sequence. A
bulk command may be delivered even when a Sony body omits its response; the
transport therefore distinguishes complete Bulk OUT delivery from a missing
response and clears the halted pipe before resuming.

### Preview freezes or the app crashes during a tap

Only one capture may run at a time. The library joins the live-view coroutine
before `0x9207`, bounds the whole capture to 25 seconds, and restarts live view
once after cleanup. If the device still freezes, capture a logcat excerpt for
`PtpTransport`, `SonyPtpCamera`, and `UsbCameraManager`; a physical USB replug
may be required if the Android USB kernel driver is left in a blocked state.

## Credits

- Sony PTP vendor opcodes and the live-view handle were reverse-engineered with
  reference to [**libgphoto2**](http://www.gphoto.org/) and the
  [**Sony Camera Remote SDK**](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)
  protocol behavior.
- BLE-era inspiration and Sony Alpha protocol notes from
  [**alpharemote**](https://github.com/Staacks/alpharemote) by Sebastian Staacks.

## License

[MIT](LICENSE) © 2026 Andrew Gallo. Not affiliated with or endorsed by Sony.
