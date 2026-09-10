#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
ADB="${ADB:-$SDK_ROOT/platform-tools/adb}"
TOKENIZER="${1:-$PROJECT_DIR/app/src/main/assets/models/sentencepiece.model}"
MODEL="$PROJECT_DIR/app/src/main/assets/models/embeddinggemma-300m.tflite"
PACKAGE="com.example.hjp"
RUNNER="$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner"
DEVICE_MODELS="/sdcard/Android/data/$PACKAGE/files/models"

[[ -x "$ADB" ]] || { echo "adb not found: $ADB" >&2; exit 69; }
[[ -f "$MODEL" ]] || { echo "Embedding model not found: $MODEL" >&2; exit 66; }
[[ -f "$TOKENIZER" ]] || {
  echo "Compatible sentencepiece.model is required. Pass its path as argument 1." >&2
  echo "It must come from litert-community/embeddinggemma-300m." >&2
  exit 66
}

DEVICE_COUNT="$($ADB devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
[[ "$DEVICE_COUNT" -eq 1 ]] || { echo "Exactly one authorized device required; found $DEVICE_COUNT" >&2; exit 69; }
ABI="$($ADB shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$ABI" == arm64* ]] || { echo "ARM64 required; connected ABI=$ABI" >&2; exit 69; }

cd "$PROJECT_DIR"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}" \
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest

$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$ADB shell mkdir -p "$DEVICE_MODELS"
$ADB push "$TOKENIZER" "$DEVICE_MODELS/sentencepiece.model"

$ADB shell am instrument -w \
  -e class com.example.hjp.HjpDatabaseMigrationInstrumentedTest,com.example.hjp.RyeongRoomSearchInstrumentedTest \
  "$RUNNER"
$ADB shell am instrument -w \
  -e class com.example.hjp.EmbeddingGemmaArm64InstrumentedTest \
  "$RUNNER"

echo "ARM64 model-backed search -> get -> compose draft E2E passed. No message was sent."
