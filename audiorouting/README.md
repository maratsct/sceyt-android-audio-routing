## AudioRouting Library

Production‑ready, Twilio‑style audio routing for Android VoIP / call SDKs.

It discovers and manages the main call audio devices:

- **Bluetooth HFP/HSP headsets** (call audio profile)
- **Wired headsets (3.5mm / analog USB‑C)**
- **Built‑in earpiece**
- **Speakerphone**

The library provides:

- **Automatic routing** with configurable device priority
- **Manual device selection** with lock/unlock semantics
- **Reactive state** via `StateFlow`
- **Robust Bluetooth SCO management** with retry + timeout
- **Audio focus** and `MODE_IN_COMMUNICATION` handling
- **Explicit state machine** for predictable behavior

---

## Gradle Setup

In your app module:

```kotlin
dependencies {
    implementation(project(":audiorouting"))
}
```

Minimum supported SDK: **24**  
Tested target range: **Android 12 – 15**

---

## Required Permissions

Add (some are compile‑time only on older APIs):

```xml
<!-- AndroidManifest.xml (app) -->
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<!-- Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

On Android 12+ you must request `BLUETOOTH_CONNECT` at runtime before Bluetooth routing will work.

---

## Core Concepts

- **`AudioRouter`** – main public API used by your call SDK / app.
- **`AudioDevice`** – sealed class representing a concrete route:
  - `BluetoothHeadset`
  - `WiredHeadset`
  - `Earpiece`
  - `Speakerphone`
- **`RoutingState`** – high‑level router state:
  - `IDLE`, `STARTED`, `ACTIVATED`, etc.
- **`AudioRouterConfig`** – configuration (priority, logging, SCO tuning).
- **`StateFlow`‑based observables**:
  - `availableDevices: StateFlow<List<AudioDevice>>`
  - `selectedDevice: StateFlow<AudioDevice?>`
  - `routingState: StateFlow<RoutingState>`

---

## Default Device Priority

The default automatic routing order is:

1. `BluetoothHeadset` (classic HFP/HSP)
2. `WiredHeadset`
3. `Earpiece`
4. `Speakerphone`

You can override this with `AudioRouterConfig.preferredDeviceOrder`.

---

## Basic Usage

### 1. Create `AudioRouter`

Typically you do this once per call stack (e.g. in a DI container or call manager).

```kotlin
val config = AudioRouterConfig(
    // Optional: custom device priority
    // preferredDeviceOrder = listOf(...)
    loggingEnabled = true
)

val audioRouter: AudioRouter = AudioRouter.create(
    context = applicationContext,
    config = config
)
```

### 2. Observe State (UI / Call Logic)

Use `StateFlow` from your ViewModel / presenter:

```kotlin
val availableDevices = audioRouter.availableDevices
val selectedDevice = audioRouter.selectedDevice
val routingState = audioRouter.routingState
```

In Jetpack Compose you can collect them with `collectAsState()` to render a device picker similar to the sample `MainActivity`.

### 3. Start / Activate / Stop

```kotlin
// Start listening to device changes and manage audio focus state
audioRouter.start(
    listener = object : AudioRouter.Listener {
        override fun onAvailableDevicesChanged(devices: List<AudioDevice>) { /* ... */ }
        override fun onSelectedDeviceChanged(device: AudioDevice?) { /* ... */ }
        override fun onRoutingStateChanged(state: RoutingState) { /* ... */ }
    }
)

// When the call ends
audioRouter.release()
```

`start()` / `stop()` control listening + internal state.  

---

## Device Selection

### Manual Selection

```kotlin
// Get current devices (e.g. from UI)
val devices = audioRouter.availableDevices.value
val speaker = devices.filterIsInstance<AudioDevice.Speakerphone>().firstOrNull()

if (speaker != null) {
    audioRouter.selectDevice(speaker) // Locks manual selection
}
```

While a manual device is selected, the router will **not** auto‑switch on new connections unless the selected device disappears.

### Clear Manual Selection

```kotlin
audioRouter.clearManualSelection()
// Router resumes automatic priority‑based routing
```

### Custom Priority

```kotlin
val config = AudioRouterConfig(
    preferredDeviceOrder = listOf(
        AudioDevice.BluetoothHeadset::class,
        AudioDevice.WiredHeadset::class,
        AudioDevice.Earpiece::class,
        AudioDevice.Speakerphone::class
    )
)
```

You can also change the order at runtime:

```kotlin
audioRouter.setPreferredDeviceOrder(
    listOf(
        AudioDevice.Speakerphone::class,
        AudioDevice.Earpiece::class
    )
)
```

---

## Refreshing Devices (Permissions / Settings Changes)

If you ask the user for Bluetooth permissions or they toggle Bluetooth / USB after the router is started, you can force a rescan:

```kotlin
audioRouter.refreshDevices()
```

This re‑discovers Bluetooth headsets (HFP/HSP), wired headsets, earpiece and
speakerphone and syncs them with the internal state machine.

---

## Bluetooth Support Details

- **HFP/HSP (classic Bluetooth):**
  - Managed by `BluetoothHandler` using `BluetoothHeadset` profile.
  - Uses SCO for low‑latency, narrowband call audio.
- **BLE Audio & Hearing Aids:**
  - Discovered via `AudioDeviceManager` using `AudioDeviceInfo` types:
    - `TYPE_BLE_HEADSET`, `TYPE_BLE_SPEAKER`, `TYPE_HEARING_AID`
  - Routed via `AudioManager.setCommunicationDevice()` on Android 12+.

Profiles explicitly **not** targeted:

- A2DP music playback profiles
- Generic media‑only BT speakers (unless exposed as communication devices)

This keeps the behavior focused on **call audio**.

---

## Testing

The library includes:

- **Unit tests** for the state machine, SCO manager, and priority logic.
- **Integration tests** for device selection and routing behavior.
- **Instrumented tests** (optional) for real devices, including Bluetooth.

To run:

```bash
./gradlew :audiorouting:test
./gradlew :audiorouting:connectedDebugAndroidTest
```

For Bluetooth instrumented tests on Android 12+ you must grant:

```bash
adb shell pm grant com.sceyt.audiorouting.lib.test android.permission.BLUETOOTH_CONNECT
```