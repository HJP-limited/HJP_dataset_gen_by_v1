#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

FORBIDDEN_FILES="$(git ls-files | grep -E '(^|/)(build|\.gradle|\.kotlin)/|\.litertlm$|\.apk$|\.aab$|\.xnnpack_cache_|(^|/)local\.properties$' || true)"
if [[ -n "$FORBIDDEN_FILES" ]]; then
  echo "Forbidden generated or binary files are tracked:" >&2
  echo "$FORBIDDEN_FILES" >&2
  exit 1
fi

PRIVATE_ROOT="/""Users/"
PRIVATE_PATHS="$(git grep -n -F "$PRIVATE_ROOT" -- . ':!scripts/verify-repository-hygiene.sh' || true)"
if [[ -n "$PRIVATE_PATHS" ]]; then
  echo "Personal absolute paths are tracked:" >&2
  echo "$PRIVATE_PATHS" >&2
  exit 1
fi

echo "Repository hygiene checks passed."
