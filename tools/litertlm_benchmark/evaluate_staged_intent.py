#!/usr/bin/env python3
"""Evaluate staged extraction separately from orchestration and content."""

from __future__ import annotations

import argparse
import csv
import json
import re
from pathlib import Path
from typing import Any

from evaluate_intent_orchestrator import OLD_FAILURES, evaluate, expected_intent, read


ROOT = Path(__file__).resolve().parent
DEFAULT_INPUT = ROOT / "results/gemma4-staged-intent-e-64.jsonl"
DEFAULT_REPORT = ROOT / "reports/gemma4-staged-intent-e-64_report.md"
DEFAULT_CSV = ROOT / "reports/gemma4-staged-intent-e-64_evaluation.csv"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate Stage 1/Stage 2 benchmark JSONL.")
    parser.add_argument("input", type=Path, nargs="?", default=DEFAULT_INPUT)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--csv", type=Path, default=DEFAULT_CSV)
    parser.add_argument("--ratings", type=Path)
    return parser.parse_args()


def expected_stage_intent(case: dict[str, Any]) -> str:
    explicit = case.get("expected_stage_intent")
    if isinstance(explicit, str) and explicit:
        return explicit
    legacy = expected_intent(case)
    if legacy == "ANSWER_ONLY":
        return "GENERAL"
    if legacy == "CLARIFY":
        test_id = str(case["id"])
        if "email" in test_id:
            return "COMPOSE_EMAIL"
        if "sms" in test_id or "phone" in test_id:
            return "COMPOSE_SMS"
        if "calendar" in test_id:
            return "CREATE_CALENDAR_EVENT"
        if "update" in test_id:
            return "UPDATE_CONTACT"
        return "GENERAL"
    if legacy == "UNSUPPORTED":
        test_id = str(case["id"])
        if "send_email" in test_id:
            return "COMPOSE_EMAIL"
        if "send_sms" in test_id:
            return "COMPOSE_SMS"
        if "calendar" in test_id:
            return "CREATE_CALENDAR_EVENT"
        if "contact" in test_id:
            return "UPDATE_CONTACT"
        return "GENERAL"
    return legacy


def expected_action(case: dict[str, Any]) -> str:
    explicit = case.get("expected_action")
    if isinstance(explicit, str) and explicit:
        return explicit
    if case.get("should_call_tool"):
        return "EXECUTE"
    category = case.get("category")
    if category == "unsupported":
        return "UNSUPPORTED"
    if category == "clarification" or category == "safe_failure":
        return "CLARIFY"
    if case["id"] in {"no_tool_email_example_02", "no_tool_draft_preview_02"}:
        return "ANSWER_OR_PREVIEW"
    return "ANSWER"


def classification_ok(value: Any, case: dict[str, Any]) -> tuple[bool, bool]:
    value = value if isinstance(value, dict) else {}
    expected = expected_action(case)
    action_ok = (
        value.get("action") in {"ANSWER", "PREVIEW"}
        if expected == "ANSWER_OR_PREVIEW"
        else value.get("action") == expected
    )
    return (
        value.get("intent") == expected_stage_intent(case),
        action_ok,
    )


def required_slot_ok(row: dict[str, Any], plan_key: str) -> bool:
    case = row["test_case"]
    if expected_action(case) != "EXECUTE":
        return True
    if expected_stage_intent(case) == "GET_CURRENT_DATETIME":
        return row.get(plan_key) is not None
    plan = row.get(plan_key) or {}
    if plan.get("intent") != expected_intent(case):
        return False
    explicit_slots = case.get("expected_slots")
    if isinstance(explicit_slots, dict) and explicit_slots:
        field_map = {
            "contact_name": "recipient_value",
            "title": "calendar_title",
        }
        def normalized_equal(key: str, actual: Any, expected: Any) -> bool:
            if not isinstance(actual, str) or not isinstance(expected, str):
                return actual == expected
            actual_text = re.sub(r"\s+", " ", actual).strip()
            expected_text = re.sub(r"\s+", " ", expected).strip()
            if key in {"contact_name", "recipient_value"}:
                actual_text = re.sub(r"(?:씨께|님께|씨|님)$", "", actual_text)
                expected_text = re.sub(r"(?:씨께|님께|씨|님)$", "", expected_text)
            if key == "title":
                for suffix in (" 일정 작성 화면", " 작성 화면", " 일정"):
                    if actual_text.endswith(suffix) and actual_text[:-len(suffix)].strip() == expected_text:
                        actual_text = expected_text
                        break
            if key == "content_goal":
                terms = [token for token in re.findall(r"[가-힣A-Za-z0-9]+", expected_text) if len(token) >= 2]
                return bool(terms) and all(term.casefold() in actual_text.casefold() for term in terms)
            return actual_text == expected_text
        return all(
            normalized_equal(key, plan.get(field_map.get(key, key)), value)
            for key, value in explicit_slots.items()
            if key not in {"attendee_type", "update_field", "update_value"}
        ) and (
            "update_field" not in explicit_slots
            or (plan.get("updates") or {}).get(explicit_slots["update_field"])
                == explicit_slots.get("update_value")
        )
    prompt = str(case["prompt"])
    expected_tools = case.get("expected_tools") or []
    expected_args = case.get("expected_arguments") or {}
    names = [
        "이메일없는사람", "전화없는사람", "검색오류사람", "상세오류사람",
        "이동명이인", "없는사람", "박없는", "김지원",
    ]
    expected_name = next((name for name in names if name in prompt), "")
    recipient_ok = True
    if "search_contacts" in expected_tools:
        recipient_ok = (
            plan.get("recipient_type") == "CONTACT_NAME"
            and plan.get("recipient_value") == expected_name
        )
    elif expected_tools == ["get_contact"]:
        recipient_ok = (
            plan.get("recipient_type") == "CARD_ID"
            and plan.get("recipient_value") == expected_args.get("card_id")
        )
    elif expected_tools and expected_tools[-1] == "open_compose":
        destination = expected_args.get("to")
        if isinstance(destination, str) and destination in prompt:
            expected_type = "EMAIL" if "@" in destination else "PHONE"
            recipient_ok = (
                plan.get("recipient_type") == expected_type
                and plan.get("recipient_value") == destination
            )
    goal_ok = True
    if expected_stage_intent(case) in {"COMPOSE_EMAIL", "COMPOSE_SMS"}:
        requirements = case.get("body_requirements") or []
        goal = str(plan.get("content_goal") or "")
        goal_ok = all(word in goal for word in requirements)
    date_ok = True
    if expected_stage_intent(case) == "CREATE_CALENDAR_EVENT":
        date_ok = bool(plan.get("date_expression") and plan.get("time_expression"))
    return bool(plan and recipient_ok and goal_ok and date_ok)


def pct(value: int, total: int) -> str:
    return "N/A" if not total else f"{100 * value / total:.1f}% ({value}/{total})"


def calendar_start_ok(source: dict[str, Any]) -> bool:
    expected = (source["test_case"].get("expected_arguments") or {}).get("start_time")
    if expected is None:
        return True
    calls = source.get("orchestrator_tool_calls") or []
    actual = next(
        (
            (call.get("arguments") or {}).get("start_time")
            for call in reversed(calls)
            if call.get("name") == "create_calendar_event"
        ),
        None,
    )
    return actual == expected


def main() -> int:
    args = parse_args()
    metadata, raw = read(args.input)
    ratings = {}
    if args.ratings:
        ratings = {
            key: float(value)
            for key, value in json.loads(args.ratings.read_text(encoding="utf-8")).items()
        }
    rows = []
    for source in raw:
        case = source["test_case"]
        initial = source.get("classification_initial")
        final = source.get("classification_final")
        b_intent, b_action = classification_ok(initial, case)
        d_intent, d_action = classification_ok(final, case)
        base = evaluate(source, ratings)
        c_slot = required_slot_ok(source, "plan_c_no_repair")
        d_slot = required_slot_ok(source, "plan_d_repair")
        stage1_attempts = (source.get("stage1") or {}).get("attempts") or []
        stage2_attempts = (source.get("stage2") or {}).get("attempts") or []
        structured_initial = bool(stage1_attempts and isinstance(stage1_attempts[0].get("parsed"), dict))
        structured_final = bool((source.get("stage1") or {}).get("success"))
        repair_used = len(stage1_attempts) > 1 or len(stage2_attempts) > 1
        rows.append({
            **base,
            "expected_stage_intent": expected_stage_intent(case),
            "expected_action": expected_action(case),
            "initial_stage_intent": (initial or {}).get("intent", ""),
            "initial_action": (initial or {}).get("action", ""),
            "final_stage_intent": (final or {}).get("intent", ""),
            "final_action": (final or {}).get("action", ""),
            "b_intent_ok": b_intent,
            "b_action_ok": b_action,
            "d_intent_ok": d_intent,
            "d_action_ok": d_action,
            "stage1_structured_initial": structured_initial,
            "stage1_structured_final": structured_final,
            "c_required_slots_ok": c_slot,
            "d_required_slots_ok": d_slot,
            "repair_used": repair_used,
            "worker_error": source.get("worker_error") or "",
            "body_generated": any(
                call.get("name") == "open_compose"
                and bool((call.get("arguments") or {}).get("body"))
                for call in (source.get("orchestrator_tool_calls") or [])
            ),
            "calendar_start_ok": calendar_start_ok(source),
        })
    tool_rows = [row for row in rows if row["expected_action"] == "EXECUTE"]
    slot_rows = [
        row for row in tool_rows if row["expected_stage_intent"] != "GET_CURRENT_DATETIME"
    ]
    clarify = [row for row in rows if row["expected_action"] == "CLARIFY"]
    unsupported = [row for row in rows if row["expected_action"] == "UNSUPPORTED"]
    answer = [
        row for row in rows
        if row["expected_action"] in {"ANSWER", "PREVIEW", "ANSWER_OR_PREVIEW"}
    ]
    multi = [
        row for row, source in zip(rows, raw)
        if len(source["test_case"].get("expected_tools") or []) > 1
    ]
    contact = [
        row for row, source in zip(rows, raw)
        if "search_contacts" in (source["test_case"].get("expected_tools") or [])
        and len(source["test_case"].get("expected_tools") or []) > 1
    ]
    dates = [
        row for row, source in zip(rows, raw)
        if "create_calendar_event" in (source["test_case"].get("expected_tools") or [])
    ]
    body_expected = [
        row for row, source in zip(rows, raw)
        if "open_compose" in (source["test_case"].get("expected_tools") or [])
    ]
    body_success = [
        row for row in body_expected
        if row["body_generated"] and row["body_keywords_ok"] and row["schema_ok"]
    ]
    rated_scores = [
        float(ratings[row["test_id"]]) if row["test_id"] in ratings else 0.0
        for row in body_expected
    ]
    body_quality = sum(rated_scores) / len(body_expected) if body_expected else 0.0
    old_recovered = sum(row["strict"] for row in rows if row["test_id"] in OLD_FAILURES)

    count = lambda collection, key: sum(bool(item[key]) for item in collection)
    metrics = {
        "stage1_intent_initial": pct(count(rows, "b_intent_ok"), len(rows)),
        "stage1_action_initial": pct(count(rows, "b_action_ok"), len(rows)),
        "structured_initial": pct(count(rows, "stage1_structured_initial"), len(rows)),
        "stage1_intent_final": pct(count(rows, "d_intent_ok"), len(rows)),
        "stage1_action_final": pct(count(rows, "d_action_ok"), len(rows)),
        "structured_final": pct(count(rows, "stage1_structured_final"), len(rows)),
        "slots_initial": pct(count(slot_rows, "c_required_slots_ok"), len(slot_rows)),
        "slots_final": pct(count(slot_rows, "d_required_slots_ok"), len(slot_rows)),
        "clarify": pct(count(clarify, "d_action_ok"), len(clarify)),
        "unsupported": pct(count(unsupported, "d_action_ok"), len(unsupported)),
        "answer_preview": pct(count(answer, "d_action_ok"), len(answer)),
        "workflow": pct(count(tool_rows, "workflow_ok"), len(tool_rows)),
        "multi": pct(count(multi, "workflow_ok"), len(multi)),
        "contact": pct(count(contact, "workflow_ok"), len(contact)),
        "date": pct(count(dates, "calendar_start_ok"), len(dates)),
        "schema": pct(count(rows, "schema_ok"), len(rows)),
        "strict": pct(count(rows, "strict"), len(rows)),
        "body_success": pct(len(body_success), len(body_expected)),
        "body_quality": f"{body_quality:.1f}/10 (실패·미평가=0, n={len(body_expected)})",
        "unsafe": str(sum(row["unsafe_execution_count"] for row in rows)),
        "false": str(count(rows, "false_completion")),
        "old_recovered": f"{old_recovered}/11",
        "model_errors": str(sum(not (row["d_intent_ok"] and row["d_action_ok"] and row["d_required_slots_ok"]) for row in rows)),
        "parser_errors": str(sum(not row["stage1_structured_final"] for row in rows)),
        "orchestrator_errors": str(count(rows, "orchestrator_error")),
    }
    ratios = {
        "intent": count(rows, "d_intent_ok") / len(rows),
        "action": count(rows, "d_action_ok") / len(rows),
        "structured": count(rows, "stage1_structured_final") / len(rows),
        "slots": count(slot_rows, "d_required_slots_ok") / len(slot_rows),
        "clarify": count(clarify, "d_action_ok") / len(clarify),
        "unsupported": count(unsupported, "d_action_ok") / len(unsupported),
        "workflow": count(tool_rows, "workflow_ok") / len(tool_rows),
        "multi": count(multi, "workflow_ok") / len(multi),
    }
    gate = (
        ratios["intent"] >= .95 and ratios["action"] >= .95
        and ratios["structured"] >= .98 and ratios["slots"] >= .95
        and ratios["clarify"] >= .95 and ratios["unsupported"] >= .95
        and ratios["workflow"] >= .90 and ratios["multi"] >= .90
        and count(rows, "schema_ok") == len(rows)
        and count(dates, "calendar_start_ok") == len(dates)
        and sum(row["unsafe_execution_count"] for row in rows) == 0
        and count(rows, "false_completion") == 0
        and body_quality >= 8.0 and old_recovered == 11
    )

    args.csv.parent.mkdir(parents=True, exist_ok=True)
    with args.csv.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    lines = [
        "# Gemma 4 E2B staged intent 평가",
        "",
        f"- 입력: `{args.input}`",
        f"- 모델 SHA-256: `{metadata.get('model_sha256', '')}`",
        f"- LiteRT constrained decoding: `{metadata.get('constrained_decoding')}`",
        f"- production gate: **{'통과' if gate else '실패'}**",
        "",
        "## A~E 비교",
        "",
        "| 지표 | A 단일 structured | B Stage 1 | C + Stage 2 | D + 단계 repair | E + 원문 본문 |",
        "|---|---:|---:|---:|---:|---:|",
        f"| intent | 53.1% | {metrics['stage1_intent_initial']} | {metrics['stage1_intent_initial']} | {metrics['stage1_intent_final']} | {metrics['stage1_intent_final']} |",
        f"| action/execute | 53.1% | {metrics['stage1_action_initial']} | {metrics['stage1_action_initial']} | {metrics['stage1_action_final']} | {metrics['stage1_action_final']} |",
        f"| 구조화 출력 | 56.3% | {metrics['structured_initial']} | {metrics['structured_initial']} | {metrics['structured_final']} | {metrics['structured_final']} |",
        f"| required slot | 67.5% | N/A | {metrics['slots_initial']} | {metrics['slots_final']} | {metrics['slots_final']} |",
        f"| workflow | 75.0% | N/A | N/A | {metrics['workflow']} | {metrics['workflow']} |",
        f"| multi-tool | 80.0% | N/A | N/A | {metrics['multi']} | {metrics['multi']} |",
        f"| 본문 생성 성공 | 기존 실패 포함 미분리 | N/A | N/A | 기존 prompt | {metrics['body_success']} |",
        f"| 본문 품질 | 6.9/10 | N/A | N/A | 6.9/10 | {metrics['body_quality']} |",
        "",
        "## 최종 지표",
        "",
    ]
    lines += [f"- {key}: {value}" for key, value in metrics.items()]
    lines += [
        "",
        "## 사례별 오류 계층",
        "",
        "| ID | 기대→최종 intent/action | Stage 2 | workflow | strict | 오류 계층 |",
        "|---|---|---|---|---|---|",
    ]
    for row in rows:
        layers = []
        if not row["d_intent_ok"] or not row["d_action_ok"] or not row["d_required_slots_ok"]:
            layers.append("MODEL")
        if not row["stage1_structured_final"]:
            layers.append("SCHEMA/PARSER")
        if row["orchestrator_error"]:
            layers.append("ORCHESTRATOR")
        lines.append(
            f"| {row['test_id']} | {row['expected_stage_intent']}/{row['expected_action']} → "
            f"{row['final_stage_intent'] or '-'}/{row['final_action'] or '-'} | "
            f"{'OK' if row['d_required_slots_ok'] else 'FAIL'} | "
            f"{'OK' if row['workflow_ok'] else 'FAIL'} | "
            f"{'PASS' if row['strict'] else 'FAIL'} | {','.join(layers) or '-'} |"
        )
    args.report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({
        "metrics": metrics,
        "gate": gate,
        "report": str(args.report),
        "csv": str(args.csv),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
