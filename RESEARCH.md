# ResMed AirMini Reverse Engineering Notes

## Objective

Extract and analyze therapy/sleep data from the ResMed AirMini ecosystem without official API access.

Current constraints:

* Android device is not rooted.
* Official AirMini app (`com.resmed.airmini`) is installed.
* App is not debuggable.
* Goal is to obtain raw therapy data and understand protocol/storage architecture.

---

# Environment

Package:

```text
com.resmed.airmini
```

ADB status:

```bash
adb devices
```

Device successfully authorized and connected.

---

# Android App Investigation

## Package Information

```bash
adb shell dumpsys package com.resmed.airmini | grep dataDir
```

Output:

```text
dataDir=/data/user/0/com.resmed.airmini
```

Internal app storage location:

```text
/data/user/0/com.resmed.airmini
```

---

## Debuggability Check

```bash
adb shell run-as com.resmed.airmini ls
```

Output:

```text
run-as: package not debuggable: com.resmed.airmini
```

Conclusion:

* Cannot access private storage via `run-as`.
* Need root or alternate extraction method.

---

## External Storage Check

```bash
adb shell ls /sdcard/Android/data/com.resmed.airmini
```

Output:

```text
No such file or directory
```

Conclusion:

* App does not appear to store data in public Android storage.
* Data likely resides entirely in private app storage.

---

# Permission Analysis

Relevant permissions:

```text
android.permission.BLUETOOTH_CONNECT
android.permission.BLUETOOTH_SCAN
android.permission.INTERNET
android.permission.ACCESS_NETWORK_STATE
android.permission.ACCESS_WIFI_STATE
android.permission.FOREGROUND_SERVICE
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.WAKE_LOCK
```

Notably absent:

```text
READ_EXTERNAL_STORAGE
WRITE_EXTERNAL_STORAGE
MANAGE_EXTERNAL_STORAGE
```

Implication:

* App is primarily BLE + cloud sync.
* No evidence of public file exports.

---

# APK Extraction

APK paths:

```text
/data/app/~~MNbLMkqJ0Ph2RRUvsc7F-A==/com.resmed.airmini-5QtCicluTfi6Ec8whsIxVg==/base.apk
/data/app/~~MNbLMkqJ0Ph2RRUvsc7F-A==/com.resmed.airmini-5QtCicluTfi6Ec8whsIxVg==/split_asset_pack.apk
/data/app/~~MNbLMkqJ0Ph2RRUvsc7F-A==/com.resmed.airmini-5QtCicluTfi6Ec8whsIxVg==/split_config.arm64_v8a.apk
/data/app/~~MNbLMkqJ0Ph2RRUvsc7F-A==/com.resmed.airmini-5QtCicluTfi6Ec8whsIxVg==/split_config.xxhdpi.apk
```

Pull command:

```bash
adb pull <apk-path>
```

Recommended tooling:

```bash
brew install jadx
jadx-gui base.apk
```

---

# Decompiled APK Findings

## Database Layer

Evidence of SQLCipher:

```text
net.sqlcipher.database.SQLiteDatabase
DaoMaster$EncryptedOpenHelper
DaoMaster$EncryptedDevOpenHelper
```

Implication:

* Local database likely encrypted.
* Even if DB file is obtained, decryption key will be required.

---

# Database Schema Findings

## Sleep Records

Table:

```sql
RMON__SLEEP_RECORD
```

Fields discovered:

```text
AHI
Leak Percentile
Pressure Percentile
Sleep Score
Usage Score
Events Score
Mask Score
Total Usage
Start Timestamp
End Timestamp
```

Purpose:

Daily summarized sleep report.

---

## Therapy Sessions

Table:

```sql
RMON__THERAPY_SESSION
```

Contains:

```text
Session Start
Session End
Total Sleep Time
Wake Time
REM Sleep
Deep Sleep
Light Sleep
Mask Type
Completion Status
```

Purpose:

Per-session therapy tracking.

---

## Sleep Events

Table:

```sql
RMON__SLEEP_EVENT
```

Contains:

```text
Timestamp
Event Type
Duration
Session Reference
```

Purpose:

Detailed respiratory event tracking.

---

## Value Items

Table:

```sql
RMON__VALUE_ITEM
```

Observed fields:

```text
VALUE (REAL)
ORDER_VALUE
SESSION_THERAPY_LEAK_ID
SESSION_THERAPY_IPAP_ID
```

Likely contains:

```text
Minute-by-minute leak values
Minute-by-minute pressure values
```

---

# Bluetooth / RPC Layer Findings

Discovered identifiers:

```text
GetLoggedDataRpcRequest
GetLoggedDataNotification
AddLoggedDataQuery
GetSessionKeyRpcRequest
GetPairKeyRpcRequest
SubscriptionNotification
StreamDataNotification
```

Strong indication of:

```text
Custom BLE RPC protocol
```

Potential flow:

```text
AirMini Device
      ↓
GetLoggedDataRpcRequest
      ↓
GetLoggedDataRpcResponse
      ↓
Therapy Data
```

---

# Therapy Payload Structures Found

Identifiers discovered:

```text
TherapyOneMinutePeriodic
PeriodicValue
RespiratoryEvents
TherapyEvents
```

Implication:

AirMini likely exposes detailed therapy telemetry directly over BLE.

Potential data available:

```text
AHI
Leak
Pressure
Respiratory Events
Usage
Minute-by-minute values
```

---

# Architecture Hypothesis

Likely architecture:

```text
AirMini Device
      ↓ BLE
Official AirMini App
      ↓
Protobuf Decoding
      ↓
SQLCipher Database
      ↓
UI
      ↓
Cloud Sync
```

Key observation:

Local database encryption is probably irrelevant if data is already decrypted before insertion.

---

# Frida Assessment

Initial idea:

Hook classes such as:

```text
AddLoggedDataQuery
CreateRecordSleepRecordQuery
CreateRecordTherapySessionQuery
```

Goal:

Intercept decoded therapy data before SQLCipher persistence.

However:

* Device is not rooted.
* User does not want to modify official app.
* Frida is therefore not the preferred path.

---

# Preferred Research Direction

## Direct BLE Client

Implement independent AirMini client.

Target architecture:

```text
Custom Client
      ↓ BLE
AirMini Device
```

Advantages:

* No root required.
* No dependency on official app.
* Avoids SQLCipher entirely.

---

# Immediate Reverse Engineering Tasks

## BLE UUID Discovery

Use:

* nRF Connect
* Android BLE scanner

Enumerate:

```text
Service UUIDs
Characteristic UUIDs
Descriptors
```

---

## Search Decompiled APK

Look for:

```java
UUID.fromString(...)
BluetoothGatt
BluetoothGattCharacteristic
writeCharacteristic(...)
setCharacteristicNotification(...)
```

Goal:

Identify:

```text
Service UUID
Request Characteristic
Notification Characteristic
```

---

## Locate RPC Transport

Search for:

```text
GetLoggedDataRpcRequest
```

Then trace to:

```java
BluetoothGattCharacteristic
writeCharacteristic
```

Expected flow:

```text
UI
 ↓
Repository
 ↓
RPC Layer
 ↓
Protobuf Serialize
 ↓
BLE Write
```

---

# Working Hypothesis

Confidence estimates:

```text
90% AirMini exposes therapy data over BLE
70% Payloads are protobuf messages
60% Authentication is pairing + session key
30% Additional challenge/response mechanism exists
```

Most promising path:

```text
Reverse engineer BLE protocol
→ Reimplement client
→ Extract therapy data directly
```

Avoid:

```text
Trying to crack SQLCipher first
```

because the protocol layer appears significantly more accessible.

---

# Next High-Value Searches in JADX

Search globally for:

```text
UUID.fromString
BluetoothGatt
BluetoothGattCharacteristic
BluetoothGattService
GetLoggedDataRpcRequest
GetLoggedDataNotification
GetSessionKeyRpcRequest
GetPairKeyRpcRequest
TherapyOneMinutePeriodic
RespiratoryEvents
PeriodicValue
RoomDatabase
SQLiteOpenHelper
EncryptedOpenHelper
retrofit
okhttp
protobuf
```

Recover BLE protocol and authentication flow.

---

# Engineering Rules & Protocol Lessons

During the reverse-engineering and implementation of this sync client, several critical protocol and runtime patterns were established:

### 1. Handling Closed-Source JNI stdout Pollution
* **The Problem**: Proprietary JNI C++ libraries (`.so`/`.dylib` files) often contain hardcoded debug logs written directly to raw standard output (`stdout` via `printf` or `std::cout`) instead of Java loggers. When redirection is used (`> output.json`), this prints strings (like `HandleResponse`) that corrupt JSON formats.
* **The Rule**: **Never assume stdout from a JNI runner is clean.** When capturing JSON generated by native processes, always implement a robust "brace-seeking" parser (e.g., searching for the first `{` byte index in Python/Java and slicing the stream) to isolate the JSON object from raw console logs.

### 2. State-Machine Driven Handshakes & Transmit Queues
* **The Problem**: Cryptographic exchanges (like Diffie-Hellman) are stateful. Passing a packet to `nativeDecode()` changes the internal library state and queues up a response packet (like `ConfirmKeyExchange`) in the library's memory *internally*, rather than returning it immediately in the function call.
* **The Rule**: **Always check JNI tx queues after decoding.** For state-driven native protocols, a single API call is not enough. You must implement a loop that reads a packet from the device, passes it to JNI, and immediately checks the JNI outbound queue (via `pullTxData()`) to see if the library generated a frame that must be written to the socket to keep the handshake alive.

### 3. Avoiding ADB/Shell Escaping with "File Pushing"
* **The Problem**: Passing complex JSON strings (like previous sync timestamps) through `adb shell "cmd 'json'"` often crashes or truncates because the double quotes in the JSON clash with the outer shell quotes.
* **The Rule**: **Favor file transfers over shell argument arrays.** For complex parameters, write the JSON to a temporary file on the host machine, push it to the device (`adb push config.json /data/local/tmp/latest_sync.json`), and let the runner parse it directly from the local disk. This avoids all command-line quoting, character-escaping, and argument-length limit bugs.

### 4. Streaming RPC & Stream-ID Association
* **The Problem**: An RPC call like `GetLoggedData` returns a synchronous confirmation first (`{"id":2, "result":{"logStreamId": X}}`), followed by asynchronous notifications (`{"method":"LoggedData", "params":{"logStreamId": X, "data": [...]}}`). Breaking the loop on the initial confirmation response leaves the actual data packets unread in the socket buffer, polluting the next request.
* **The Rule**: **Bind and wait for the stream ID.** When initiating a streaming RPC request:
  1. Read the socket to capture the stream confirmation and save the assigned `logStreamId`.
  2. Continue loop-reading notifications matching that stream ID, appending data chunks until you receive a packet marked `"complete": true`.
  3. Only then terminate the loop and allow the program to request the next resource.

### 5. Client-Side ISO 8601 Lexicographical Filtering
* **The Problem**: Querying since a timestamp (incremental sync) can lead to duplicate records at the exact boundary of the query.
* **The Rule**: ISO 8601 UTC timestamps (e.g., `2026-06-13T03:14:09.945Z`) are designed to sort perfectly lexicographically. You can safely filter out duplicate boundaries using simple string comparisons (`newTime.compareTo(lastTime) > 0`) without the performance overhead of parsing timestamps into full `Date`/`DateTime` objects.


