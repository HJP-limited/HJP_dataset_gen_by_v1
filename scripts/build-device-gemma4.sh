#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MODEL_FILE="${HJP_GEMMA4_MODEL:-}"
EXPECTED_NAME="gemma-4-E2B-it.litertlm"
EXPECTED_SIZE="2588147712"
EXPECTED_SHA256="181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
OUTPUT_APK="${HJP_DEVICE_APK_OUTPUT:-$PROJECT_DIR/dist/HJP-Gemma4-E2B-Device-arm64-debug.apk}"
GRADLE_APK="$PROJECT_DIR/app/build/outputs/apk/device/standalone/app-device-standalone.apk"

[[ -n "$MODEL_FILE" ]] || {
  echo "HJP_GEMMA4_MODEL is required." >&2
  echo "export HJP_GEMMA4_MODEL=/absolute/path/to/$EXPECTED_NAME" >&2
  exit 64
}
[[ -f "$MODEL_FILE" ]] || { echo "Gemma 4 model not found: $MODEL_FILE" >&2; exit 66; }
[[ "$(basename "$MODEL_FILE")" == "$EXPECTED_NAME" ]] || {
  echo "Model filename mismatch: expected $EXPECTED_NAME" >&2
  exit 65
}

if stat -f '%z' "$MODEL_FILE" >/dev/null 2>&1; then
  ACTUAL_SIZE="$(stat -f '%z' "$MODEL_FILE")"
else
  ACTUAL_SIZE="$(stat -c '%s' "$MODEL_FILE")"
fi
[[ "$ACTUAL_SIZE" == "$EXPECTED_SIZE" ]] || {
  echo "Model size mismatch: expected $EXPECTED_SIZE, found $ACTUAL_SIZE" >&2
  exit 65
}

if command -v shasum >/dev/null 2>&1; then
  ACTUAL_SHA256="$(shasum -a 256 "$MODEL_FILE" | awk '{print $1}')"
else
  ACTUAL_SHA256="$(sha256sum "$MODEL_FILE" | awk '{print $1}')"
fi
[[ "$ACTUAL_SHA256" == "$EXPECTED_SHA256" ]] || {
  echo "Model SHA-256 mismatch: expected $EXPECTED_SHA256, found $ACTUAL_SHA256" >&2
  exit 65
}

cd "$PROJECT_DIR"
./gradlew :app:assembleDeviceStandalone
[[ -f "$GRADLE_APK" ]] || { echo "Built APK not found: $GRADLE_APK" >&2; exit 70; }
mkdir -p "$(dirname "$OUTPUT_APK")"
cp "$GRADLE_APK" "$OUTPUT_APK"
echo "Device APK: $OUTPUT_APK"
