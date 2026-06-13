# 001 — Android App: AirMini Sync

## Background

We have already reverse-engineered the AirMini BLE/RFCOMM protocol, implemented
encryption and decryption, and successfully downloaded therapy data from the device
using our own code running on Android via ADB.

The Android app is a **productization effort**, not a research project. The goal is to
package that working implementation behind a minimal, portable UI so that it can be used
without a laptop.

Distribution will be via APK on the GitHub Releases page (sideloaded, not Play Store).

---

## Design Goal

Keep the app **intentionally small**. This is not a multi-module architecture with
repository layers, use-case classes, or dependency injection frameworks. Optimize for
readability and iteration speed. Split files only when they become genuinely unwieldy.

---

## Platform Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Language | Kotlin | Modern, concise, coroutine-native |
| Build system | Gradle Wrapper | Self-bootstrapping; works without Android Studio |
| Build config | Kotlin DSL (`build.gradle.kts`) | Consistent with Kotlin-first codebase |
| AGP | 8.5.2 | Latest stable AGP 8.x |
| Kotlin | 2.0.21 | Latest stable; ships separate Compose compiler plugin |
| Gradle | 8.7 | Minimum required for AGP 8.5 |
| JVM | JDK 17 | Required by modern AGP |
| UI toolkit | Jetpack Compose + Material3 | No XML layouts |
| Activity model | Single Activity | Compose handles navigation |
| `minSdk` | 31 (Android 12) | Modern Bluetooth permission model; eliminates legacy location-permission branches |
| `targetSdk` / `compileSdk` | 35 (Android 15) | Latest stable |

---

## Transport Clarification

The existing working implementation connects using:

- **SPP UUID**: `00001101-0000-1000-8000-00805F9B34FB`
- **`BluetoothSocket`** (RFCOMM, Bluetooth Classic)

This is **not** BLE/GATT. The two are entirely separate Android APIs.
The transport layer is therefore `BluetoothTransport` (RFCOMM), not `BleTransport` (GATT).

When connecting via the public `BluetoothSocket` API, the 32-byte system socket handshake
that was consumed in the ADB shell binder implementation is **not** present. No special
header stripping is needed.

---

## Package Namespace Decision

`libfiglib.so` contains JNI symbols hardcoded to the original package:

```
Java_com_resmed_mon_fig_FigWrapper_initialise
Java_com_resmed_mon_fig_FigWrapper_nativeDecode
Java_com_resmed_mon_fig_FigWrapper_nativeEncode
Java_com_resmed_mon_fig_FigWrapper_pullTxData
```

The native source is not available in the repo (`data/dump/` is a decompiled Java dump
from the APK, not C/C++ source). We cannot recompile `libfiglib.so`.

**Decision:** `FigWrapper` stays in `com.resmed.mon.fig`. Everything else lives in
`com.airmini.sync`. These are fully independent — the `applicationId`, app label, and
all other source files use the clean namespace.

| Thing | Value |
|---|---|
| App label (icon name) | `AirMini Sync` |
| `applicationId` (Settings → Apps, APK identity) | `com.airmini.sync` |
| All source files except `FigWrapper` | `com.airmini.sync` |
| `FigWrapper` only | `com.resmed.mon.fig` (JNI constraint) |
| `libfiglib.so` ABI | `arm64-v8a` only (physical device testing) |

---

## File Structure

```text
android-app/
├── settings.gradle.kts
├── build.gradle.kts                        # root Gradle config
├── gradlew / gradlew.bat
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
└── app/
    ├── build.gradle.kts                    # app-level compilation rules
    └── src/main/
        ├── AndroidManifest.xml
        ├── jniLibs/
        │   └── arm64-v8a/
        │       └── libfiglib.so            # copied from data/libfiglib.so
        ├── res/values/
        │   ├── strings.xml
        │   └── themes.xml
        └── java/
            ├── com/airmini/sync/
            │   ├── MainActivity.kt
            │   ├── BluetoothViewModel.kt
            │   ├── AirMiniClient.kt
            │   ├── BluetoothTransport.kt
            │   ├── AirMiniCrypto.kt
            │   └── ui/
            │       └── MainScreen.kt
            └── com/resmed/mon/fig/
                └── FigWrapper.kt           # must stay in this package (JNI)
```

---

## Architecture

```
Compose UI  (MainScreen.kt)
      ↓
BluetoothViewModel.kt
      ↓
AirMiniClient.kt
      ↓
BluetoothTransport.kt
      ↓
BluetoothSocket  (Android SDK — RFCOMM)

AirMiniCrypto.kt
      ↓
JNI / libfiglib.so
```

---

## Component Responsibilities

### `MainActivity`
- Request runtime Bluetooth permissions (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`)
- Handle Bluetooth enable flow
- Host Compose content

### `BluetoothViewModel`
- Hold UI state (`StateFlow`)
- Expose connect / download actions as suspend functions called from coroutines
- Surface log lines and errors to the UI
- Coordinate operations between `BluetoothTransport` and `AirMiniClient`

### `BluetoothTransport`
- Scan bonded devices for AirMini
- Manage `BluetoothSocket` lifecycle (connect, disconnect, reconnect)
- Expose `InputStream` / `OutputStream` to `AirMiniClient` via a `Transport` interface

Implements:

```kotlin
interface Transport {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun read(): ByteArray
    suspend fun write(data: ByteArray)
}
```

`BluetoothTransport` knows nothing about AirMini messages or encryption.

### `AirMiniClient`
- Packet framing and parsing (`bebafeca` magic header, 16-byte header + payload)
- Session establishment (`GetPairKey` / PIN exchange)
- Session key negotiation and handoff to `AirMiniCrypto`
- `GetLoggedData` request/response and stream handling
- Incremental timestamp tracking (via `SharedPreferences`)
- AirMini-specific protocol logic only; no Android UI concerns

`AirMiniClient` accepts any `Transport` implementation, making it testable outside
Android.

> **Protocol rule — `pullTxData()` loop (Rule 4):** After every `decodePacket()` call
> during the handshake phase, `AirMiniClient` must call `crypto.pullTxData()` and write
> any non-null result back to the transport immediately. The JNI library internally queues
> outbound frames (e.g. `ConfirmKeyExchange`) that must be flushed this way to advance
> the cryptographic state machine. Omitting this will cause the handshake to stall.
>
> **Protocol rule — `GetLoggedData` two-phase stream (Rule 5):** The initial response
> to `GetLoggedData` is a synchronous registration acknowledgment containing `logStreamId`.
> Subsequent data arrives as `LoggedData` notification packets. Loop-read and match on
> `logStreamId` until a batch arrives with `"complete": true`.

### `AirMiniCrypto`
- Thin Kotlin wrapper around `FigWrapper` JNI calls
- Exposes `encodePacket(json: String): ByteArray` and `decodePacket(bytes: ByteArray): String?`
- No protocol logic; no Android UI concerns

### `MainScreen`
- Device discovery and selection UI
- Connection status display
- Download trigger button
- Scrolling log output (terminal-style)
- Results display / share action

No protocol logic in UI.

---

## First Milestone (End-to-End Success Criterion)

```
Launch app
→ Discover AirMini (scan bonded devices)
→ Connect via RFCOMM (BluetoothSocket)
→ Establish protocol session (PIN → session key)
→ Download logged data (GetLoggedData for all 4 data IDs)
→ Display results in UI
```

Everything else (history views, export formats, data visualisations, settings,
diagnostics) is deferred until the milestone above works end-to-end.

---

## CI (GitHub Actions)

Trigger: push of a `v*` tag.

Steps:
1. `actions/checkout@v4`
2. `actions/setup-java@v4` — Temurin JDK 17, with `cache: gradle`
3. Android SDK — `ubuntu-latest` runners have the Android SDK pre-installed at
   `$ANDROID_HOME`. No explicit SDK install step is needed. AGP reads `ANDROID_HOME`
   automatically; `local.properties` is not required in CI.
4. `chmod +x android-app/gradlew`
5. `cd android-app && ./gradlew assembleRelease`
6. Upload `app/build/outputs/apk/release/app-release-unsigned.apk` to GitHub Release
   via `softprops/action-gh-release@v2`

Signed release builds are deferred; unsigned APKs are sufficient for internal
distribution from the Releases page.

---

## Headless / Script Workflow

The existing [`run_sync.sh`](../../run_sync.sh) already provides a headless laptop workflow:

```
Mac terminal → run_sync.sh
  → ADB push timestamps to phone
  → ADB shell: runs DeviceSync on the phone (phone connects to AirMini over RFCOMM)
  → ADB pull: JSON streamed back to Mac
  → Python merges new records into sleep_data.json
```

The Android app removes the USB + ADB dependency and makes the flow portable. Both
paths remain valid.

---

## Open Items

- [x] `libfiglib.so` ABI — arm64-v8a only. Physical device testing; no emulator needed.
- [x] Package namespace — resolved. See Package Namespace Decision section above.
- [ ] **Remove old Groovy/Java files** from `android-app/` before scaffolding Kotlin:
  - `android-app/settings.gradle`
  - `android-app/build.gradle`
  - `android-app/app/build.gradle`
  - `android-app/app/src/main/res/layout/activity_main.xml`
  - `android-app/app/src/main/res/xml/file_paths.xml`
  - `android-app/app/src/main/java/com/resmed/mon/fig/FigWrapper.java`
  - `android-app/app/src/main/java/com/resmed/mon/fig/DeviceSync.java`
- [ ] **Update `.gitignore`** — add Android build artifact entries:
  ```
  android-app/.gradle/
  android-app/build/
  android-app/app/build/
  android-app/local.properties
  android-app/**/*.apk
  ```
- [ ] **Copy `libfiglib.so`** into JNI libs directory:
  ```bash
  mkdir -p android-app/app/src/main/jniLibs/arm64-v8a
  cp data/libfiglib.so android-app/app/src/main/jniLibs/arm64-v8a/libfiglib.so
  ```
  Note: `data/` is gitignored at the root. `android-app/app/src/main/jniLibs/` is
  NOT gitignored and the `.so` should be committed alongside the source.
- [ ] **Bootstrap `gradle-wrapper.jar`** — this is a binary that cannot be written by
  an agent. After all text-based build files are in place, run once:
  ```bash
  cd android-app
  gradle wrapper --gradle-version 8.7
  ```
  Requires Gradle installed locally (`brew install gradle`). The generated
  `gradle-wrapper.jar` should be committed to the repo.
- [ ] **Update `.cursorrules`** — the file currently only documents the headless ADB
  workflow (Rules 1–6 are specific to `app_process` shell execution). Add a section
  documenting the Android app architecture, build commands, and which rules apply
  (Rules 1 and 2 do NOT apply to the app; Rules 3–6 remain relevant).
