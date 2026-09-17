#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

python3 -m unittest discover -s tools/agent_eval_multiturn_v1 -p 'test_*.py' -v
./gradlew :agent-core:test --offline --rerun-tasks
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline --rerun-tasks

echo "Agent checkpoint verification passed."
