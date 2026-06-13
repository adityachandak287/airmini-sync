#!/bin/bash
# build.sh — local build helper for AirMini Sync
#
# Usage:
#   ./build.sh           # debug build (default)
#   ./build.sh --release # release build (unsigned)
#
# Requires JDK 21 installed (brew install --cask temurin@21).
# Wraps the JAVA_HOME selection so you don't have to remember it.
# CI does not use this script — GitHub Actions sets JAVA_HOME via actions/setup-java.

set -euo pipefail

# ── JDK 21 selection ─────────────────────────────────────────────────────────

# Note: java_home -v treats the version as a minimum (>= 21), not an exact match.
# Verify the returned JDK is actually version 21.x by checking the binary directly.
JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
if [[ -z "$JAVA_HOME" ]] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "21\.'; then
  echo "Error: JDK 21 not found (java_home -v returns the minimum version, not an exact match)."
  echo "Install it with: brew install --cask temurin@21"
  exit 1
fi
export JAVA_HOME

# ── Android SDK detection ─────────────────────────────────────────────────────

# Use ANDROID_HOME if already set in the environment, otherwise check the
# standard macOS location written by Android Studio / sdkmanager.
if [[ -z "${ANDROID_HOME:-}" ]]; then
  ANDROID_HOME="$HOME/Library/Android/sdk"
fi

if [[ ! -d "$ANDROID_HOME" ]]; then
  echo "Error: Android SDK not found at $ANDROID_HOME"
  echo ""
  echo "Install it with:"
  echo "  brew install --cask android-commandlinetools"
  echo "  mkdir -p \$HOME/Library/Android/sdk"
  echo "  sdkmanager --sdk_root=\$HOME/Library/Android/sdk --licenses"
  echo "  sdkmanager --sdk_root=\$HOME/Library/Android/sdk \"platforms;android-35\" \"build-tools;35.0.0\""
  exit 1
fi
export ANDROID_HOME

# Gradle reads local.properties for sdk.dir — generate it if missing or stale.
# This file is gitignored (machine-specific).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_PROPS="$SCRIPT_DIR/local.properties"
echo "sdk.dir=$ANDROID_HOME" > "$LOCAL_PROPS"

# ── Build target ──────────────────────────────────────────────────────────────

TASK="assembleDebug"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if [[ "${1:-}" == "--release" ]]; then
  TASK="assembleRelease"
  # Prompt securely for keystore password
  echo "Release build requested. Signing requires Keystore password."
  read -s -p "Enter Keystore Password: " KEYSTORE_PW
  echo ""
  
  if [[ -n "$KEYSTORE_PW" ]]; then
    export KEYSTORE_PASSWORD="$KEYSTORE_PW"
    export KEY_PASSWORD="$KEYSTORE_PW"

    echo "input=$KEYSTORE_PW"
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
  else
    echo "Error: Keystore password is required to sign the release APK."
    exit 1
  fi
fi

# ── Run ───────────────────────────────────────────────────────────────────────

cd "$SCRIPT_DIR"

echo "Building: $TASK"
echo "  JAVA_HOME:    $JAVA_HOME"
echo "  ANDROID_HOME: $ANDROID_HOME"
./gradlew "$TASK"


# ── Output ────────────────────────────────────────────────────────────────────

if [[ -f "$APK_PATH" ]]; then
  echo ""
  echo "✓ Build successful"
  echo "  APK: $SCRIPT_DIR/$APK_PATH"
fi
