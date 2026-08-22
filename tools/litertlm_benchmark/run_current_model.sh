#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
MODEL="$REPO_ROOT/models/hjp-agent.litertlm"
PRESET="$SCRIPT_DIR/hjp_tools_preset.py"
RESULTS="$SCRIPT_DIR/results"
REPORTS="$SCRIPT_DIR/reports"
VENV_PYTHON="$SCRIPT_DIR/.venv/bin/python"
VENV_CLI="$SCRIPT_DIR/.venv/bin/litert-lm"

if [ ! -f "$MODEL" ]; then
  echo "error: model file not found: $MODEL" >&2
  exit 2
fi
if [ ! -f "$PRESET" ]; then
  echo "error: preset file not found: $PRESET" >&2
  exit 2
fi

if [ -x "$VENV_CLI" ]; then
  CLI="$VENV_CLI"
elif command -v litert-lm >/dev/null 2>&1; then
  CLI=$(command -v litert-lm)
else
  echo "error: litert-lm CLI not found; prepare .venv as described in README.md" >&2
  exit 2
fi

if [ -x "$VENV_PYTHON" ]; then
  PYTHON="$VENV_PYTHON"
elif command -v python3 >/dev/null 2>&1; then
  PYTHON=$(command -v python3)
else
  echo "error: Python not found" >&2
  exit 2
fi

mkdir -p "$RESULTS" "$REPORTS"
STAMP=$(date -u "+%Y%m%dT%H%M%SZ")
OUTPUT="$RESULTS/current-hjp-agent_cpu_smoke_$STAMP.jsonl"

"$PYTHON" "$SCRIPT_DIR/run_benchmark.py" \
  --model "$MODEL" \
  --model-name current-hjp-agent \
  --backend cpu \
  --cli "$CLI" \
  --preset "$PRESET" \
  --test-case current_datetime_01 \
  --test-case compose_email_explicit_01 \
  --test-case compose_email_context_01 \
  --output "$OUTPUT" \
  "$@"

"$PYTHON" "$SCRIPT_DIR/evaluate_results.py" \
  "$OUTPUT" \
  --output-dir "$REPORTS"
