#!/usr/bin/env python3
"""Summarize repeated staged Gemma runs without treating orchestrator output as model output."""

from __future__ import annotations

import argparse
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path

from evaluate_intent_orchestrator import evaluate, read
from evaluate_staged_intent import classification_ok, required_slot_ok


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, math.ceil(len(ordered) * fraction) - 1)]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--pattern", default="repeat-20260801-*.jsonl")
    parser.add_argument("--output", type=Path, required=True)
    options = parser.parse_args()
    grouped = defaultdict(list)
    for path in sorted(options.directory.glob(options.pattern)):
        _, rows = read(path)
        for row in rows:
            grouped[row["test_id"]].append(row)
    result = {"runs": {}, "semantic_repeat": {"status": "NOT_RUN", "reason": "no physical ARM64 device"}}
    for test_id, rows in sorted(grouped.items()):
        durations = [
            sum(run.get("duration_seconds", 0.0) for run in row.get("intent_runs", []) + row.get("content_runs", []))
            for row in rows
        ]
        intent_ok = action_ok = slot_ok = strict_ok = worker_ok = 0
        classifications = set()
        plans = set()
        sequences = set()
        bodies = set()
        timeouts = 0
        for row in rows:
            intent, action = classification_ok(row.get("classification_final"), row["test_case"])
            intent_ok += intent
            action_ok += action
            slot_ok += required_slot_ok(row, "plan_d_repair")
            strict_ok += evaluate(row, {})["strict"]
            runs = row.get("intent_runs", []) + row.get("content_runs", [])
            worker_ok += not row.get("worker_error") and all(run.get("return_code") == 0 for run in runs)
            timeouts += sum(bool(run.get("timed_out")) for run in runs)
            classifications.add(json.dumps(row.get("classification_final"), sort_keys=True, ensure_ascii=False))
            plans.add(json.dumps(row.get("plan_d_repair"), sort_keys=True, ensure_ascii=False))
            sequences.add(tuple(row.get("execution_sequence") or []))
            for call in row.get("orchestrator_tool_calls") or []:
                if call.get("name") == "open_compose":
                    bodies.add(str((call.get("arguments") or {}).get("body") or ""))
        count = len(rows)
        result["runs"][test_id] = {
            "count": count,
            "process_success": worker_ok,
            "intent_success": intent_ok,
            "action_success": action_ok,
            "required_slot_success": slot_ok,
            "strict_success": strict_ok,
            "classification_variants": len(classifications),
            "plan_variants": len(plans),
            "workflow_sequence_variants": len(sequences),
            "body_variants": len(bodies),
            "latency_p50_seconds": statistics.median(durations),
            "latency_p95_seconds": percentile(durations, .95),
            "timeouts": timeouts,
        }
    result["total_runs"] = sum(value["count"] for value in result["runs"].values())
    result["crashes"] = sum(
        value["count"] - value["process_success"] for value in result["runs"].values()
    )
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
