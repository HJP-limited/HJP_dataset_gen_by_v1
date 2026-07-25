#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_FILE="${1:-${HJP_DEVICE_APK_OUTPUT:-$PROJECT_DIR/dist/HJP-Gemma4-E2B-RyeongSearch-Device-arm64-debug.apk}}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
ADB="${ADB:-${SDK_ROOT:+$SDK_ROOT/platform-tools/adb}}"
PACKAGE_NAME="com.example.hjp"
ACTIVITY_NAME="$PACKAGE_NAME/.MainActivity"

[[ -f "$APK_FILE" ]] || { echo "APK not found: $APK_FILE" >&2; exit 66; }
[[ -n "$ADB" && -x "$ADB" ]] || { echo "adb not found; set ANDROID_SDK_ROOT or ADB." >&2; exit 69; }

DEVICE_COUNT="$("$ADB" devices | awk 'NR > 1 && $2 == "device" {count++} END {print count + 0}')"
[[ "$DEVICE_COUNT" -eq 1 ]] || {
  echo "Exactly one authorized Android device is required; found $DEVICE_COUNT." >&2
  "$ADB" devices -l >&2
  exit 69
}
SERIAL="$("$ADB" devices | awk 'NR > 1 && $2 == "device" {print $1}')"
IS_EMULATOR="$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')"
[[ "$IS_EMULATOR" != "1" ]] || {
  echo "The Gemma 4 device APK must be tested on a physical arm64 device." >&2
  exit 70
}

"$ADB" -s "$SERIAL" install -r "$APK_FILE"
"$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE_NAME"
"$ADB" -s "$SERIAL" shell am start -W -n "$ACTIVITY_NAME"
