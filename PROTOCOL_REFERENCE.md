# ResMed AirMini Protocol & Schema Reference

This document serves as the technical reference for the ResMed AirMini's communication protocol, telemetry structure, and internal schemas.

---

## 1. Protocol Architecture & Flow
* **Connection Type**: Classic Bluetooth Serial Port Profile (SPP) / RFCOMM.
* **Service UUID**: `00001101-0000-1000-8000-00805F9B34FB`
* **Encryption Layer**: Custom cryptographic handshake. Decryption/encryption keys are negotiated via Diffie-Hellman exchanging pairing keys.
* **Payload Format**: Structured JSON-RPC notifications and requests encapsulated in the encrypted layer.

### RPC Interaction Flow
1. **GetPairKey**: Sent with the 4-digit PIN to negotiate the `sessionKey`.
2. **Upgrade Cipher**: Encrypted transmission is activated using the JNI-generated session key.
3. **GetLoggedData**: Initiates telemetry sync.
   - Initial Response: Synchronous registration confirmation containing the assigned `logStreamId`.
   - Streaming Notifications: Asynchronous `LoggedData` events matching the stream ID, received iteratively until `complete=true`.

---

## 2. Telemetry Streams & Schemas

The AirMini exposes therapy and sleep logs across four primary streams:

### A. UsageEvents-TherapyStatusEvent
Contains usage metrics and mask status tracking (sessions mapped by MaskOn/MaskOff events).
* **Key Fields**:
  - `time` (ISO-8601 UTC timestamp)
  - `event` (e.g., `MaskOn`, `MaskOff`, `TherapyStart`, `TherapyStop`)

### B. TherapyEvents-RespiratoryEvent
Contains respiratory abnormalities detected during therapy.
* **Key Fields**:
  - `time` (ISO-8601 UTC timestamp)
  - `event` (e.g., `ObstructiveApnea`, `CentralApnea`, `Hypopnea`)
  - `durationSeconds` (Duration of the event)

### C. TherapyOneMinutePeriodic-InspiratoryPressure
Provides minute-by-minute periodic inspiratory pressure logs.
* **Key Fields**:
  - `startTime` (ISO-8601 start boundary)
  - `values` (Array of numeric pressure readings in cmH2O)

### D. TherapyOneMinutePeriodic-Leak
Provides periodic mask leak rate logs.
* **Key Fields**:
  - `startTime` (ISO-8601 start boundary)
  - `values` (Array of numeric leak values in L/min)

---

## 3. Engineering Rules & Protocol Lessons

### Rule 1: JNI stdout Pollution
The proprietary native libraries writing debug statements directly to `stdout` instead of using the standard logging pipeline. When parsing raw outputs, a brace-seeking logic (seeking first `{` byte) must be used to isolate valid JSON payloads.

### Rule 2: Handshake Transmit Queue Flushing
State-driven cryptographic handshakes automatically stage successive packets internally in the native library memory. You must call `pullTxData()` and flush queued packets to the bluetooth socket immediately after every decode operation to keep the handshake alive.

### Rule 3: Client-Side ISO 8601 Lexicographical Filtering
ISO 8601 UTC strings format naturally to sort lexicographically. Duplicate boundary checking and time window filtering can be safely accomplished via string comparison (`newTime.compareTo(lastTime) > 0`) avoiding timezone overhead.

### Rule 4: PII Security
Under no circumstances should `.json` sync files (`sleep_data.json`, etc.) containing sensitive medical telemetry be checked into source control.
