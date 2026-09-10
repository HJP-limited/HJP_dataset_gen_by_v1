#!/usr/bin/env bash
set -euo pipefail

if [[ $# -gt 2 ]]; then
  echo "Usage: $0 [model-path] [apk-path]" >&2
  exit 64
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OFFICIAL_MODEL_FILE="$PROJECT_DIR/models/gemma-4-E2B-it.litertlm"
OFFICIAL_MODEL_SHA256="181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
MODEL_FILE="${1:-$OFFICIAL_MODEL_FILE}"
APK_FILE="${2:-$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk}"
SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
ADB="${ADB:-$SDK_ROOT/platform-tools/adb}"
PACKAGE_NAME="com.example.hjp"
ACTIVITY_NAME="com.example.hjp/.MainActivity"
DEVICE_MODEL_DIR="/sdcard/Android/data/$PACKAGE_NAME/files/models"
DEVICE_MODEL_FILE="$DEVICE_MODEL_DIR/hjp-agent.litertlm"

[[ -f "$MODEL_FILE" ]] || { echo "Model not found: $MODEL_FILE" >&2; exit 66; }
[[ -f "$APK_FILE" ]] || { echo "APK not found: $APK_FILE" >&2; exit 66; }
[[ -x "$ADB" ]] || { echo "adb not found: $ADB" >&2; exit 69; }

if command -v shasum >/dev/null 2>&1; then
  HOST_HASH="$(shasum -a 256 "$MODEL_FILE" | awk '{ print $1 }')"
else
  HOST_HASH="$(sha256sum "$MODEL_FILE" | awk '{ print $1 }')"
fi

# A no-argument deployment is the production path. Pin it to the inspected artifact bytes so a
# renamed or accidentally replaced legacy file is rejected before the APK or device is touched.
if [[ $# -eq 0 && "$HOST_HASH" != "$OFFICIAL_MODEL_SHA256" ]]; then
  echo "Official model SHA-256 mismatch: expected=$OFFICIAL_MODEL_SHA256 actual=$HOST_HASH" >&2
  exit 74
fi

DEVICE_COUNT="$($ADB devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
if [[ "$DEVICE_COUNT" -ne 1 ]]; then
  echo "Exactly one authorized Android device is required; found $DEVICE_COUNT." >&2
  $ADB devices -l >&2
  exit 69
fi

echo "Installing: $APK_FILE"
$ADB install -r "$APK_FILE"
$ADB shell mkdir -p "$DEVICE_MODEL_DIR"

echo "Deploying model: $DEVICE_MODEL_FILE"
$ADB push "$MODEL_FILE" "$DEVICE_MODEL_FILE"

DEVICE_HASH="$($ADB shell sha256sum "$DEVICE_MODEL_FILE" | tr -d '\r' | awk '{ print $1 }')"

if [[ "$HOST_HASH" != "$DEVICE_HASH" ]]; then
  echo "Model SHA-256 mismatch: host=$HOST_HASH device=$DEVICE_HASH" >&2
  exit 74
fi

echo "Model SHA-256 verified: $HOST_HASH"
$ADB shell am force-stop "$PACKAGE_NAME"
$ADB shell am start -W -n "$ACTIVITY_NAME"
