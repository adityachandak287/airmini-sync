# AirMini Sync

A standalone Jetpack Compose Android application that connects directly to the ResMed AirMini CPAP machine via Bluetooth Classic (RFCOMM SPP), fetches decrypted telemetry sleep records, and exports them to a standard JSON format.

This completely standalone app handles the authenticated handshake (DH Exchange), encrypted stream decryption via a JNI C++ library (`libfiglib.so`), and file sharing entirely on-device.

---

## Architecture & Project Structure

The project has been fully migrated to a standalone Android application structure under `android-app/`:

* **Jetpack Compose UI**: Clean, light/dark mode-aware material layout presenting device selection, PIN input, interactive terminal logging, and data exporting.
* **JNI Decryption**: Packages the decryption wrapper inside `/jniLibs/` to parse and decode binary telemetry packets directly inside the JVM runtime.
* **Dual-Build Configurations**: Supports side-by-side installations of developer/debug builds and production release builds.

---

## Local Development & Builds

The project includes a terminal helper script `build.sh` inside the `android-app/` directory to handle compiler targets and Java configurations automatically.

### Step 1: Install Prerequisites
Ensure you have JDK 21 and the Android SDK command-line tools installed:
```bash
brew install --cask temurin@21
brew install --cask android-commandlinetools
```

### Step 2: Build and Run Debug APK (Recommended for Testing)
The debug version installs side-by-side under the package name `com.airmini.sync.debug` with the launcher label **AirMini Sync Debug**:
```bash
cd android-app
./build.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 3: Build and Run Signed Release APK
To build a production-ready signed release APK (which installs under the clean package name `com.airmini.sync`), you must supply the password to the keystore prompt:
```bash
cd android-app
./build.sh --release
# Enter your Keystore password at the prompt
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## Release Pipeline & CI/CD (GitHub Actions)

The repository is configured with a fully automated release pipeline in `.github/workflows/build-apk.yml`. 

Whenever you push a Git tag to GitHub, the CI pipeline automatically:
1. Decodes your release keystore from secrets.
2. Compiles a production-ready signed Release APK.
3. Generates release notes automatically from your commit logs since the last tag.
4. Marks the release as a **pre-release** if the tag contains a modifier (e.g., `0.0.1-alpha1`) or a **stable release** if it is a clean semantic version (e.g., `0.0.1`).

### How to trigger a Release:
```bash
# 1. Commit and push your changes to main
git push origin main

# 2. Tag the release
git tag 0.0.1-alpha1

# 3. Push the tag to trigger the pipeline
git push origin 0.0.1-alpha1
```