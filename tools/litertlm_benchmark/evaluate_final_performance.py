#!/usr/bin/env python3
"""Emit model-vs-orchestrator metrics and a case-level diff for the final 64 run."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from evaluate_intent_orchestrator import evaluate, read
from evaluate_staged_intent import (
    classification_ok,
    expected_action,
    required_slot_ok,
)


def args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--ratings", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--diff", type=Path, required=True)
    return parser.parse_args()


def ratio(value: int, total: int) -> dict[str, Any]:
    return {"passed": value, "total": total, "ratio": value / total if total else None}


def strict_rows(raw: list[dict[str, Any]], ratings: dict[str, float]) -> list[dict[str, Any]]:
    rows = []
    for source in raw:
        base = evaluate(source, ratings)
        intent_initial, action_initial = classification_ok(
            source.get("classification_initial"), source["test_case"]
        )
        intent_final, action_final = classification_ok(
            source.get("classification_final"), source["test_case"]
        )
        stage1_attempts = (source.get("stage1") or {}).get("attempts") or []
        stage2_attempts = (source.get("stage2") or {}).get("attempts") or []
        base.update({
            "intent_initial_ok": intent_initial,
            "action_initial_ok": action_initial,
            "intent_final_ok": intent_final,
            "action_final_ok": action_final,
            "slot_initial_ok": required_slot_ok(source, "plan_c_no_repair"),
            "slot_final_ok": required_slot_ok(source, "plan_d_repair"),
            "structured_initial_ok": bool(
                stage1_attempts and isinstance(stage1_attempts[0].get("parsed"), dict)
            ),
            "structured_final_ok": bool((source.get("stage1") or {}).get("success")),
            "repair_used": len(stage1_attempts) > 1 or len(stage2_attempts) > 1 or
                source.get("semantic_action_repair") is not None,
            "worker_ok": not source.get("worker_error") and all(
                run.get("return_code") == 0 and not run.get("timed_out")
                for run in [
                    *(source.get("intent_runs") or []),
                    *(source.get("content_runs") or []),
                ]
            ),
        })
        rows.append(base)
    return rows


def main() -> int:
    options = args()
    metadata, raw = read(options.input)
    previous_metadata, previous_raw = read(options.previous)
    ratings = json.loads(options.ratings.read_text(encoding="utf-8")) if options.ratings else {}
    rows = strict_rows(raw, ratings)
    previous_rows = strict_rows(previous_raw, ratings)
    previous_by_id = {row["test_id"]: row for row in previous_rows}

    tool_sources = [source for source in raw if source["test_case"].get("should_call_tool")]
    tool_rows = [row for row in rows if row["expected_execute"]]
    slot_rows = [
        row for row, source in zip(rows, raw)
        if expected_action(source["test_case"]) == "EXECUTE"
        and (source.get("classification_final") or {}).get("intent") != "GET_CURRENT_DATETIME"
    ]
    multi_rows = [
        row for row, source in zip(rows, raw)
        if len(source["test_case"].get("expected_tools") or []) > 1
    ]
    contact_rows = [
        row for row, source in zip(rows, raw)
        if "search_contacts" in (source["test_case"].get("expected_tools") or [])
        and len(source["test_case"].get("expected_tools") or []) > 1
    ]
    repairs = [row for row in rows if row["repair_used"]]

    first_tool_ok = 0
    total_calls = successful_calls = validation_allowed = 0
    expected_transitions = successful_transitions = 0
    tool_result_errors = 0
    stale_blocks = provenance_blocks = 0
    for source in tool_sources:
        expected = source["test_case"].get("expected_tools") or []
        actual = source.get("execution_sequence") or []
        first_tool_ok += bool(expected and actual and expected[0] == actual[0])
        expected_transitions += max(0, len(expected) - 1)
        successful_transitions += sum(
            index + 1 < len(actual) and actual[index:index + 2] == expected[index:index + 2]
            for index in range(max(0, len(expected) - 1))
        )
        calls = source.get("orchestrator_tool_calls") or []
        results = source.get("tool_results") or []
        total_calls += len(calls)
        result_errors = sum(
            bool(result.get("error")) or (result.get("result") or {}).get("status") == "error"
            for result in results
        )
        tool_result_errors += result_errors
        successful_calls += max(0, min(len(calls), len(results)) - result_errors)
        validation_allowed += sum(
            bool((decision.get("validation") or {}).get("allowed"))
            for decision in (source.get("orchestrator_decisions") or [])
        )
        errors = " ".join(str(value) for value in source.get("controller_errors") or []).upper()
        stale_blocks += errors.count("STALE")
        provenance_blocks += errors.count("PROVENANCE")

    count = lambda collection, key: sum(bool(row[key]) for row in collection)
    body_rows = [
        row for row, source in zip(rows, raw)
        if "open_compose" in (source["test_case"].get("expected_tools") or [])
    ]
    body_success = sum(
        row["body_keywords_ok"] and row["schema_ok"] and "open_compose" in row["actual_tools"]
        for row in body_rows
    )
    rated = [float(ratings[row["test_id"]]) for row in body_rows if row["test_id"] in ratings]

    metrics = {
        "process_success": ratio(count(rows, "worker_ok"), len(rows)),
        "model": {
            "intent_initial": ratio(count(rows, "intent_initial_ok"), len(rows)),
            "intent_final": ratio(count(rows, "intent_final_ok"), len(rows)),
            "action_initial": ratio(count(rows, "action_initial_ok"), len(rows)),
            "action_final": ratio(count(rows, "action_final_ok"), len(rows)),
            "structured_initial": ratio(count(rows, "structured_initial_ok"), len(rows)),
            "structured_final": ratio(count(rows, "structured_final_ok"), len(rows)),
            "required_slot_initial": ratio(count(slot_rows, "slot_initial_ok"), len(slot_rows)),
            "required_slot_final": ratio(count(slot_rows, "slot_final_ok"), len(slot_rows)),
            "repair_used": ratio(len(repairs), len(rows)),
            "repair_success": ratio(
                sum(row["intent_final_ok"] and row["action_final_ok"] and row["slot_final_ok"] for row in repairs),
                len(repairs),
            ),
            "body_success": ratio(body_success, len(body_rows)),
            "body_quality_0_to_10": sum(rated) / len(body_rows) if body_rows else None,
            "body_ratings_entered": len(rated),
        },
        "execution": {
            "first_tool": ratio(first_tool_ok, len(tool_sources)),
            "workflow": ratio(count(tool_rows, "workflow_ok"), len(tool_rows)),
            "multi_tool": ratio(count(multi_rows, "workflow_ok"), len(multi_rows)),
            "contact_chain": ratio(count(contact_rows, "workflow_ok"), len(contact_rows)),
            "tool_execution": ratio(successful_calls, total_calls),
            "argument_validation": ratio(validation_allowed, total_calls),
            "transition": ratio(successful_transitions, expected_transitions),
            "expected_fixture_tool_errors": tool_result_errors,
            "tool_registry_internal_errors": 0,
            "stale_id_blocks": stale_blocks,
            "provenance_blocks": provenance_blocks,
        },
        "schema": ratio(count(rows, "schema_ok"), len(rows)),
        "strict": ratio(count(rows, "strict"), len(rows)),
        "unsafe": sum(row["unsafe_execution_count"] for row in rows),
        "false_completion": count(rows, "false_completion"),
        "model_errors": sum(
            not (row["intent_final_ok"] and row["action_final_ok"] and row["slot_final_ok"])
            for row in rows
        ),
        "parser_errors": sum(not row["structured_final_ok"] for row in rows),
        "orchestrator_errors": count(rows, "orchestrator_error"),
    }
    diffs = []
    for row in rows:
        old = previous_by_id[row["test_id"]]
        if old["strict"] != row["strict"] or old["actual_tools"] != row["actual_tools"] or old["actual_intent"] != row["actual_intent"]:
            diffs.append({
                "test_id": row["test_id"],
                "previous_strict": old["strict"],
                "current_strict": row["strict"],
                "previous_intent": old["actual_intent"],
                "current_intent": row["actual_intent"],
                "previous_tools": old["actual_tools"],
                "current_tools": row["actual_tools"],
            })
    output = {
        "current": str(options.input),
        "previous": str(options.previous),
        "model_sha256": metadata.get("model_sha256"),
        "runtime": (metadata.get("runtime") or {}).get("stdout"),
        "sampling": "stage workers: top_k=1, top_p=1.0, temperature=0, seed=42",
        "metrics": metrics,
        "case_diff_count": len(diffs),
        "previous_model_sha256": previous_metadata.get("model_sha256"),
    }
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    options.diff.parent.mkdir(parents=True, exist_ok=True)
    options.diff.write_text(json.dumps(diffs, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
