#!/usr/bin/env python3
"""BFCL-style layer metrics plus tau-bench-style final-state evaluation."""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Optional


ROOT = Path(__file__).resolve().parents[2]
BENCHMARK = ROOT / "tools/litertlm_benchmark"
sys.path.insert(0, str(BENCHMARK))

from evaluate_intent_orchestrator import evaluate as legacy_evaluate  # noqa: E402
from evaluate_intent_orchestrator import false_completion, read  # noqa: E402
from evaluate_results import nested_equal, schema_errors  # noqa: E402
from evaluate_staged_intent import (  # noqa: E402
    classification_ok,
    expected_action,
    expected_stage_intent,
    required_slot_ok,
)


PLACEHOLDER = re.compile(r"\[[^]]+]|client님|ooo님|당신의 이름", re.I)
UNSUPPORTED_NAMES = {"send_email", "send_sms", "delete_contact", "delete_calendar_event"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--held-out-reference", type=Path)
    parser.add_argument("--manual-ratings", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--csv", type=Path)
    parser.add_argument("--bootstrap-samples", type=int, default=10000)
    parser.add_argument("--seed", type=int, default=20260801)
    return parser.parse_args()


def load_references(path: Optional[Path]) -> dict[str, dict[str, Any]]:
    if path is None:
        return {}
    return {
        row["id"]: row
        for row in (json.loads(line) for line in path.read_text(encoding="utf-8").splitlines())
        if row.get("id")
    }


def ratio(passed: int, total: int) -> dict[str, Any]:
    return {"passed": passed, "total": total, "ratio": passed / total if total else None}


def percentile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return math.nan
    index = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * q)))
    return ordered[index]


def bootstrap_ci(values: list[int], samples: int, seed: int) -> list[float] | None:
    if not values:
        return None
    rng = random.Random(seed)
    n = len(values)
    means = [sum(values[rng.randrange(n)] for _ in range(n)) / n for _ in range(samples)]
    return [percentile(means, .025), percentile(means, .975)]


def prf(labels: list[str], predictions: list[str]) -> dict[str, Any]:
    classes = sorted(set(labels) | set(predictions))
    per_class = {}
    for label in classes:
        tp = sum(a == label and b == label for a, b in zip(labels, predictions))
        fp = sum(a != label and b == label for a, b in zip(labels, predictions))
        fn = sum(a == label and b != label for a, b in zip(labels, predictions))
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        per_class[label] = {"precision": precision, "recall": recall, "f1": f1, "support": labels.count(label)}
    return {
        "macro_f1": sum(item["f1"] for item in per_class.values()) / len(per_class) if per_class else None,
        "per_class": per_class,
        "confusion": {
            actual: dict(Counter(pred for exp, pred in zip(labels, predictions) if exp == actual))
            for actual in classes
        },
    }


def allowed_sequence(case: dict[str, Any], sequence: list[str]) -> bool:
    trajectories = case.get("allowed_trajectories")
    if isinstance(trajectories, list) and trajectories:
        return sequence in trajectories
    return sequence == (case.get("expected_tools") or [])


def final_arguments_ok(case: dict[str, Any], calls: list[dict[str, Any]]) -> bool:
    expected_tools = case.get("expected_tools") or []
    expected = case.get("expected_arguments") or {}
    if not expected or not expected_tools:
        return True
    final = next((call for call in reversed(calls) if call.get("name") == expected_tools[-1]), None)
    return bool(final and nested_equal(final.get("arguments") or {}, expected))


def row_metrics(source: dict[str, Any], ratings: dict[str, float]) -> dict[str, Any]:
    case = source["test_case"]
    base = legacy_evaluate(source, ratings)
    initial_intent_ok, initial_action_ok = classification_ok(source.get("classification_initial"), case)
    final_intent_ok, final_action_ok = classification_ok(source.get("classification_final"), case)
    calls = source.get("orchestrator_tool_calls") or []
    sequence = [str(call.get("name")) for call in calls]
    decisions = source.get("orchestrator_decisions") or []
    results = source.get("tool_results") or []
    expected_tools = case.get("expected_tools") or []
    expected_act = expected_action(case)
    should_execute = expected_act == "EXECUTE"
    forbidden = set(case.get("forbidden_tools") or []) | UNSUPPORTED_NAMES
    unsafe = int(source.get("unsafe_execution_count") or 0) + sum(name in forbidden for name in sequence)
    stale = sum("STALE" in str(error).upper() and "BLOCK" not in str(error).upper()
                for error in source.get("controller_errors") or [])
    wrong_person = int(source.get("wrong_person_lookup_count") or 0)
    schema_ok = not any(schema_errors(call) for call in calls)
    validation_ok = all((decision.get("validation") or {}).get("allowed") for decision in decisions)
    result_errors = sum(
        (item.get("result") or {}).get("status") == "error" or bool(item.get("error"))
        for item in results
    )
    tool_execution_ok = len(results) >= len(calls) and result_errors == 0
    policy_ok = unsafe == 0 and stale == 0 and wrong_person == 0
    workflow_ok = allowed_sequence(case, sequence)
    args_ok = final_arguments_ok(case, calls)
    final_state_ok = workflow_ok and args_ok and schema_ok and policy_ok
    if not should_execute:
        final_state_ok = not sequence and policy_ok
    final_text = str(source.get("final_response") or "")
    false_done = false_completion(final_text)
    final_state_ok = final_state_ok and not false_done
    # Expected backend failures are correct safe terminal states, not successful executions.
    expected_error = bool((case.get("fixture") or {}).get("search_error") or
                          (case.get("fixture") or {}).get("detail_error") or
                          (case.get("fixture") or {}).get("compose_error"))
    if expected_error:
        final_state_ok = workflow_ok and result_errors > 0 and policy_ok and not false_done
    first_tool_ok = (
        not expected_tools and not sequence
        or bool(expected_tools and sequence and expected_tools[0] == sequence[0])
    )
    expected_transitions = max(0, len(expected_tools) - 1)
    successful_transitions = sum(
        sequence[index:index + 2] == expected_tools[index:index + 2]
        for index in range(expected_transitions)
    )
    min_calls = len(case.get("minimum_required_tools") or expected_tools)
    efficiency = len(sequence) / min_calls if min_calls else (1.0 if not sequence else math.inf)
    body_call = next((call for call in reversed(calls) if call.get("name") == "open_compose"), None)
    body_args = (body_call or {}).get("arguments") or {}
    body = str(body_args.get("body") or "")
    subject = str(body_args.get("subject") or "")
    body_requirements = case.get("body_requirements") or []
    body_rule_ok = bool(body) and not PLACEHOLDER.search(body + "\n" + subject) and not false_completion(body)
    body_fact_ok = all(str(word).casefold() in body.casefold() for word in body_requirements)
    structured = bool((source.get("stage1") or {}).get("success"))
    repaired = any(len((source.get(stage) or {}).get("attempts") or []) > 1 for stage in ("stage1", "stage2")) or source.get("semantic_action_repair") is not None
    repair_success = repaired and final_intent_ok and final_action_ok and required_slot_ok(source, "plan_d_repair")
    task_success = final_state_ok and body_rule_ok and body_fact_ok
    strict = final_intent_ok and final_action_ok and required_slot_ok(source, "plan_d_repair") and task_success
    if "open_compose" not in expected_tools:
        task_success = final_state_ok
        strict = final_intent_ok and final_action_ok and required_slot_ok(source, "plan_d_repair") and task_success
    return {
        "test_id": case["id"], "split": case.get("split", "legacy"), "category": case.get("category"),
        "expected_intent": expected_stage_intent(case),
        "actual_intent": (source.get("classification_final") or {}).get("intent", "<MISSING>"),
        "expected_action": expected_act,
        "actual_action": (source.get("classification_final") or {}).get("action", "<MISSING>"),
        "intent_initial_ok": initial_intent_ok, "intent_final_ok": final_intent_ok,
        "action_initial_ok": initial_action_ok, "action_final_ok": final_action_ok,
        "structured_ok": structured, "required_slot_ok": required_slot_ok(source, "plan_d_repair"),
        "repair_used": repaired, "repair_success": repair_success,
        "first_tool_ok": first_tool_ok, "workflow_ok": workflow_ok,
        "schema_ok": schema_ok, "arguments_ok": args_ok, "validation_ok": validation_ok,
        "tool_execution_ok": tool_execution_ok, "tool_result_errors": result_errors,
        "expected_transitions": expected_transitions,
        "successful_transitions": successful_transitions,
        "final_state_ok": final_state_ok, "task_success": task_success,
        "strict_pipeline_success": strict,
        "policy_ok": policy_ok, "unsafe": unsafe, "false_completion": false_done,
        "wrong_person_lookup": wrong_person, "stale_id_execution": stale,
        "tool_efficiency": efficiency, "actual_call_count": len(sequence), "minimum_call_count": min_calls,
        "body_generated": bool(body), "body_rule_ok": body_rule_ok, "body_fact_ok": body_fact_ok,
        "manual_body_score": ratings.get(case["id"]),
        "duration_seconds": sum(float(run.get("duration_seconds") or 0) for run in
                                [*(source.get("intent_runs") or []), *(source.get("content_runs") or [])]),
        "timeout": any(bool(run.get("timed_out")) for run in
                       [*(source.get("intent_runs") or []), *(source.get("content_runs") or [])]),
        "crash": any((run.get("return_code") not in {0, None}) for run in
                     [*(source.get("intent_runs") or []), *(source.get("content_runs") or [])]),
        "legacy_strict": base["strict"],
    }


def metric(rows: list[dict[str, Any]], key: str) -> dict[str, Any]:
    values = [int(bool(row[key])) for row in rows]
    result = ratio(sum(values), len(values))
    result["bootstrap_95_ci"] = bootstrap_ci(values, GLOBAL.bootstrap_samples, GLOBAL.seed)
    return result


def main() -> int:
    global GLOBAL
    GLOBAL = parse_args()
    metadata, raw = read(GLOBAL.input)
    references = load_references(GLOBAL.held_out_reference)
    for source in raw:
        identifier = source.get("test_id")
        if identifier in references:
            source["test_case"] = references[identifier]
    ratings = json.loads(GLOBAL.manual_ratings.read_text(encoding="utf-8")) if GLOBAL.manual_ratings else {}
    all_rows = [row_metrics(source, ratings) for source in raw]
    multiturn_probe = [row for row in all_rows if row["category"] == "multiturn"]
    pairs = [
        (row, source) for row, source in zip(all_rows, raw)
        if row["category"] != "multiturn"
    ]
    rows = [item[0] for item in pairs]
    raw = [item[1] for item in pairs]
    labels_intent = [row["expected_intent"] for row in rows]
    labels_action = [
        row["actual_action"]
        if row["expected_action"] == "ANSWER_OR_PREVIEW" and row["actual_action"] in {"ANSWER", "PREVIEW"}
        else ("ANSWER" if row["expected_action"] == "ANSWER_OR_PREVIEW" else row["expected_action"])
        for row in rows
    ]
    predictions_intent = [row["actual_intent"] for row in rows]
    predictions_action = [row["actual_action"] for row in rows]
    initial_predictions_intent = [
        (source.get("classification_initial") or {}).get("intent", "<MISSING>")
        for source in raw
    ]
    initial_predictions_action = [
        (source.get("classification_initial") or {}).get("action", "<MISSING>")
        for source in raw
    ]
    tool_rows = [row for row, source in zip(rows, raw) if source["test_case"].get("expected_action") == "EXECUTE" or source["test_case"].get("should_call_tool")]
    slot_rows = [
        row for row, source in zip(rows, raw)
        if expected_action(source["test_case"]) == "EXECUTE"
        and expected_stage_intent(source["test_case"]) != "GET_CURRENT_DATETIME"
    ]
    multi_rows = [row for row, source in zip(rows, raw) if len(source["test_case"].get("minimum_required_tools") or source["test_case"].get("expected_tools") or []) > 1]
    contact_rows = [row for row, source in zip(rows, raw) if "search_contacts" in (source["test_case"].get("expected_tools") or []) and len(source["test_case"].get("expected_tools") or []) > 1]
    body_rows = [row for row, source in zip(rows, raw) if "open_compose" in (source["test_case"].get("expected_tools") or [])]
    clarify_rows = [row for row in rows if row["expected_action"] == "CLARIFY"]
    unsupported_rows = [row for row in rows if row["expected_action"] == "UNSUPPORTED"]
    answer_rows = [
        row for row in rows
        if row["expected_action"] in {"ANSWER", "PREVIEW", "ANSWER_OR_PREVIEW"}
    ]
    no_tool_rows = [
        row for row, source in zip(rows, raw)
        if not source["test_case"].get("should_call_tool")
    ]
    repeated: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        repeated[row["test_id"]].append(row)
    reliability = {
        "pass_at_1": metric([group[0] for group in repeated.values()], "task_success"),
        "strict_pass_at_1": metric(
            [group[0] for group in repeated.values()],
            "strict_pipeline_success",
        ),
    }
    for k in (3, 5):
        groups = [group for group in repeated.values() if len(group) >= k]
        reliability[f"pass_pow_{k}"] = ratio(sum(all(item["task_success"] for item in group[:k]) for group in groups), len(groups))
        reliability[f"strict_pass_pow_{k}"] = ratio(
            sum(all(item["strict_pipeline_success"] for item in group[:k]) for group in groups),
            len(groups),
        )
        reliability[f"eligible_tasks_{k}"] = len(groups)
    durations = [row["duration_seconds"] for row in rows]
    manual = [float(row["manual_body_score"]) for row in body_rows if row["manual_body_score"] is not None]
    slot_counts: dict[str, Counter[str]] = defaultdict(Counter)
    hallucinated_slots = total_model_slots = 0
    for source in raw:
        expected_slots = source["test_case"].get("expected_slots") or {}
        actual_slots = source.get("slots_final") or {}
        if not expected_slots:
            continue
        for field, expected_value in expected_slots.items():
            actual_value = actual_slots.get(field)
            if actual_value == expected_value:
                slot_counts[field]["tp"] += 1
            else:
                slot_counts[field]["fn"] += 1
                if field in actual_slots:
                    slot_counts[field]["fp"] += 1
        for field, value in actual_slots.items():
            total_model_slots += 1
            if field not in expected_slots and field != "tone":
                hallucinated_slots += 1
                slot_counts[field]["fp"] += 1
    slot_prf = {}
    for field, counts in sorted(slot_counts.items()):
        tp, fp, fn = counts["tp"], counts["fp"], counts["fn"]
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        slot_prf[field] = {
            "precision": precision, "recall": recall,
            "f1": 2 * precision * recall / (precision + recall) if precision + recall else 0.0,
            "tp": tp, "fp": fp, "fn": fn,
        }
    total_calls = sum(row["actual_call_count"] for row in rows)
    successful_calls = total_calls - sum(row["tool_result_errors"] for row in rows)
    expected_transitions = sum(row["expected_transitions"] for row in rows)
    successful_transitions = sum(row["successful_transitions"] for row in rows)
    output = {
        "input": str(GLOBAL.input), "metadata": metadata,
        "case_count": len(all_rows), "single_turn_evaluated": len(rows),
        "multiturn_requires_session_runner": len(multiturn_probe),
        "model": {
            "intent_initial_accuracy": metric(rows, "intent_initial_ok"),
            "intent_initial_prf": prf(labels_intent, initial_predictions_intent),
            "intent_accuracy": metric(rows, "intent_final_ok"), "intent_prf": prf(labels_intent, predictions_intent),
            "action_initial_accuracy": metric(rows, "action_initial_ok"),
            "action_initial_prf": prf(labels_action, initial_predictions_action),
            "action_accuracy": metric(rows, "action_final_ok"), "action_prf": prf(labels_action, predictions_action),
            "structured_output": metric(rows, "structured_ok"), "required_slot": metric(slot_rows, "required_slot_ok"),
            "clarify_accuracy": metric(clarify_rows, "action_final_ok"),
            "unsupported_accuracy": metric(unsupported_rows, "action_final_ok"),
            "answer_preview_accuracy": metric(answer_rows, "action_final_ok"),
            "repair_rate": metric(rows, "repair_used"),
            "repair_success": metric([row for row in rows if row["repair_used"]], "repair_success"),
            "body_success": metric(body_rows, "body_generated"), "body_rule_pass": metric(body_rows, "body_rule_ok"),
            "body_fact_preservation": metric(body_rows, "body_fact_ok"),
            "body_human_mean": sum(manual) / len(body_rows) if body_rows else None,
            "body_human_rated": len(manual),
            "slot_prf": slot_prf,
            "hallucinated_slot_ratio": ratio(hallucinated_slots, total_model_slots),
        },
        "agent": {
            "task_success": metric(rows, "task_success"), "final_state_match": metric(rows, "final_state_ok"),
            "no_tool_accuracy": metric(no_tool_rows, "final_state_ok"),
            "strict_pipeline_success": metric(rows, "strict_pipeline_success"),
            "first_tool": metric(tool_rows, "first_tool_ok"), "workflow": metric(tool_rows, "workflow_ok"),
            "multi_tool": metric(multi_rows, "workflow_ok"), "contact_chain": metric(contact_rows, "workflow_ok"),
            "schema": metric(rows, "schema_ok"), "argument_validation": metric(tool_rows, "validation_ok"),
            "tool_execution_by_task": metric(tool_rows, "tool_execution_ok"),
            "tool_execution_by_call": ratio(successful_calls, total_calls),
            "transition": ratio(successful_transitions, expected_transitions),
            "policy_compliance": metric(rows, "policy_ok"),
            "mean_tool_efficiency": sum(row["tool_efficiency"] for row in rows) / len(rows) if rows else None,
            "unsafe": sum(row["unsafe"] for row in rows),
            "false_completion": sum(row["false_completion"] for row in rows),
            "wrong_person_lookup": sum(row["wrong_person_lookup"] for row in rows),
            "stale_id_execution": sum(row["stale_id_execution"] for row in rows),
        },
        "reliability": reliability,
        "runtime": {
            "timeout": sum(row["timeout"] for row in rows), "crash": sum(row["crash"] for row in rows),
            "latency_p50_seconds": percentile(durations, .5), "latency_p95_seconds": percentile(durations, .95),
        },
        "failure_counts": {
            "MODEL_INTENT": sum(not row["intent_final_ok"] for row in rows),
            "MODEL_ACTION": sum(row["intent_final_ok"] and not row["action_final_ok"] for row in rows),
            "MODEL_SLOT": sum(row["intent_final_ok"] and row["action_final_ok"] and not row["required_slot_ok"] for row in rows),
            "MODEL_CONTENT": sum(not row["body_fact_ok"] for row in body_rows),
            "SCHEMA_OR_PARSER": sum(not row["structured_ok"] or not row["schema_ok"] for row in rows),
            "ORCHESTRATOR": sum(row["required_slot_ok"] and not row["workflow_ok"] for row in rows),
            "TOOL_BACKEND": sum(row["tool_result_errors"] > 0 for row in rows),
        },
    }
    GLOBAL.output.parent.mkdir(parents=True, exist_ok=True)
    GLOBAL.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    csv_path = GLOBAL.csv or GLOBAL.output.with_suffix(".csv")
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    with csv_path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader(); writer.writerows(rows)
    def show(item: dict[str, Any]) -> str:
        return f"{item['passed']}/{item['total']} ({100 * item['ratio']:.1f}%)" if item.get("ratio") is not None else "N/A"
    lines = [
        "# Agent evaluation", "", f"- input: `{GLOBAL.input}`",
        f"- single-turn cases: {len(rows)}; multi-turn session-runner cases: {len(multiturn_probe)}", "",
        "| Layer | Metric | Result |", "|---|---|---:|",
        f"| Model | intent | {show(output['model']['intent_accuracy'])} |",
        f"| Model | action | {show(output['model']['action_accuracy'])} |",
        f"| Model | required slot | {show(output['model']['required_slot'])} |",
        f"| Model | structured output | {show(output['model']['structured_output'])} |",
        f"| Orchestrator | first tool | {show(output['agent']['first_tool'])} |",
        f"| Orchestrator | workflow | {show(output['agent']['workflow'])} |",
        f"| ToolRegistry/mock | execution task | {show(output['agent']['tool_execution_by_task'])} |",
        f"| End state | task success | {show(output['agent']['task_success'])} |",
        f"| Reliability | pass@1 | {show(output['reliability']['pass_at_1'])} |",
        "", "95% CI는 JSON의 각 binary metric에 bootstrap percentile 방식으로 저장했다.",
        "본문 human mean은 미입력 case를 0점으로 포함하며 LLM judge를 사용하지 않는다.",
    ]
    GLOBAL.report.parent.mkdir(parents=True, exist_ok=True)
    GLOBAL.report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
