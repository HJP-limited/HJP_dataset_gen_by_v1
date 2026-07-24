#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_APK="${HJP_EMULATOR_APK_OUTPUT:-$PROJECT_DIR/dist/HJP-Agent-Emulator-debug.apk}"
GRADLE_APK="$PROJECT_DIR/app/build/outputs/apk/emulator/debug/app-emulator-debug.apk"

cd "$PROJECT_DIR"
./gradlew :app:assembleEmulatorDebug
[[ -f "$GRADLE_APK" ]] || { echo "Built APK not found: $GRADLE_APK" >&2; exit 70; }
mkdir -p "$(dirname "$OUTPUT_APK")"
cp "$GRADLE_APK" "$OUTPUT_APK"

if unzip -Z1 "$OUTPUT_APK" | grep -Eq '\.litertlm$|\.xnnpack_cache_'; then
  echo "Emulator APK unexpectedly contains a model or cache." >&2
  exit 65
fi
echo "Emulator APK: $OUTPUT_APK"
