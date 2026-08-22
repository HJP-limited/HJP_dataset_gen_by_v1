#!/usr/bin/env python3
"""Capture reproducibility metadata without mutating the project or devices."""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]


def command(*args: str) -> dict[str, Any]:
    run = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=False)
    return {"command": list(args), "return_code": run.returncode,
            "stdout": run.stdout.strip(), "stderr": run.stderr.strip()}


def file_record(relative: str) -> dict[str, Any]:
    path = ROOT / relative
    if not path.is_file():
        return {"path": relative, "exists": False}
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"path": relative, "exists": True, "size": path.stat().st_size,
            "sha256": digest.hexdigest()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path,
                        default=ROOT / "tools/agent_eval/results/baseline_manifest.json")
    args = parser.parse_args()
    payload = {
        "captured_at_utc": datetime.now(timezone.utc).isoformat(),
        "git": {
            "head": command("git", "rev-parse", "HEAD"),
            "branch": command("git", "branch", "--show-current"),
            "status": command("git", "status", "--short"),
            "diff_stat": command("git", "diff", "--stat"),
        },
        "artifacts": [
            file_record("models/gemma-4-E2B-it.litertlm"),
            file_record("app/src/main/assets/models/embeddinggemma-300m.tflite"),
            file_record("app/src/main/assets/models/sentencepiece.model"),
            file_record("app/build/outputs/apk/debug/app-debug.apk"),
        ],
        "runtime": {
            "platform": platform.platform(),
            "python": platform.python_version(),
            "litert_lm": command(
                str(ROOT / "tools/litertlm_benchmark/.venv/bin/litert-lm"), "--version"
            ),
            "adb_devices": command(
                "/opt/homebrew/share/android-commandlinetools/platform-tools/adb",
                "devices", "-l",
            ),
        },
        "model_settings": {
            "backend": "cpu",
            "constrained_decoding": True,
            "top_k": 1,
            "top_p": 1.0,
            "temperature": 0.0,
            "seed": 42,
            "stage_repair_limit": 1,
            "content_repair_limit": 1,
        },
        "dependencies": {
            "android_litert_lm": "0.13.1",
            "mac_litert_lm": "0.14.0",
            "embedding_rag_sdk": "0.3.0",
            "room": "2.8.3",
            "database_version": 3,
            "migration": "MIGRATION_2_3",
        },
        "benchmark": {
            "legacy_staged_cases": 64,
            "legacy_multiturn_cases": 40,
            "agent_eval_manifest": file_record("tools/agent_eval/data/manifest.json"),
            "baseline_raw": file_record(
                "tools/litertlm_benchmark/results/gemma4-staged-final-performance-20260801.jsonl"
            ),
        },
        "published_baseline": {
            "intent": [64, 64], "action": [60, 64], "required_slot": [38, 39],
            "workflow": [39, 40], "multi_tool": [15, 15], "contact_chain": [11, 11],
            "schema": [64, 64], "strict": [54, 64], "body_success": [15, 17],
            "body_quality_0_to_10": 8.1, "unsupported": [4, 7],
            "multiturn": [40, 40], "unsafe": 0, "false_completion": 0,
        },
    }
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
