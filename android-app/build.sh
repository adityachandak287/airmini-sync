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

if ! JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null); then
  echo "Error: JDK 21 not found."
  echo "Install it with: brew install --cask temurin@21"
  exit 1
fi
export JAVA_HOME

# ── Build target ──────────────────────────────────────────────────────────────

TASK="assembleDebug"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if [[ "${1:-}" == "--release" ]]; then
  TASK="assembleRelease"
  APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
fi

# ── Run ───────────────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "Building: $TASK (JAVA_HOME=$JAVA_HOME)"
./gradlew "$TASK"

# ── Output ────────────────────────────────────────────────────────────────────

if [[ -f "$APK_PATH" ]]; then
  echo ""
  echo "✓ Build successful"
  echo "  APK: $SCRIPT_DIR/$APK_PATH"
fi
