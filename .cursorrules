# AirMini Sync Agent Rules & Context

This file provides context and operational rules for AI coding assistants working in this repository.

---

## 1. Project Architecture
* **Platform**: Android App (Jetpack Compose + Material 3)
* **Communication Protocol**: Classic Bluetooth Serial Port Profile (SPP) / RFCOMM (`00001101-0000-1000-8000-00805F9B34FB`).
* **Encryption Layer**: Handled natively by `libfiglib.so` loaded from `jniLibs`. High-level Kotlin code uses JNI (`FigWrapper.kt`) to encode/decode bytes.

---

## 2. Key Files & Folders
* [android-app/](file:///Users/aditya/projects/personal/airmini-sync/android-app): Contains the modern Compose Android application.
* [scripts/stats.py](file:///Users/aditya/projects/personal/airmini-sync/scripts/stats.py): Compiles metrics and telemetry statistics from `sleep_data.json`.
* [scripts/prune.py](file:///Users/aditya/projects/personal/airmini-sync/scripts/prune.py): Filters and truncates historical data before a cutoff date.

---

## 3. Operational Rules for Agents

### Rule 1: JNI Package Namespace Constraint
* The package namespace `com.resmed.mon.fig.FigWrapper` cannot be refactored because the JNI binary `libfiglib.so` expects that hardcoded path.

### Rule 2: Cryptographic Handshake State Loops
* Handshake packets (like `GetPairKey`) prompt JNI to automatically queue subsequent response frames (like `ConfirmKeyExchange`) in its internal outbound queue. The sync loop must write these transient frames (using `FigWrapper.pullTxData()`) to the socket to progress the state.

### Rule 3: Streaming RPC Boundaries
* `GetLoggedData` returns a synchronous stream registration confirming the `logStreamId`. You must read and ignore this initial response, then loop-read incoming notifications matching that ID until a batch is flagged with `"complete": true`.

### Rule 4: Git-Ignore Sleep Telemetry
* Under **no circumstances** should `sleep_data.json`, `new_sleep_data.json`, or `latest_sync.json` be committed to git. They are personal medical data (PII) and must remain git-ignored.
