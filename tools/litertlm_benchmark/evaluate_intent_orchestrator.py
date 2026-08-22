#!/usr/bin/env python3
"""Evaluate structured intent separately from workflow orchestration."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any

from evaluate_results import false_completion, nested_equal, schema_errors


ROOT = Path(__file__).resolve().parent
DEFAULT_INPUT = ROOT / "results/gemma4-intent-workflow-orchestrator-64.jsonl"
DEFAULT_REPORT = ROOT / "reports/gemma4-intent-workflow-orchestrator-64_report.md"
DEFAULT_CSV = ROOT / "reports/gemma4-intent-workflow-orchestrator-64_manual.csv"
OLD_FAILURES = {
    "calendar_absolute_01",
    "compose_sms_reschedule_01",
    "contact_calendar_chain_01",
    "contact_update_chain_01",
    "relative_calendar_tomorrow_01",
    "relative_calendar_next_monday_01",
    "relative_contact_calendar_01",
    "clarify_calendar_time_01",
    "unsupported_delete_contact_01",
    "invalid_email_01",
    "missing_contact_email_01",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Evaluate intent-workflow orchestrator JSONL."
    )
    parser.add_argument("input", type=Path, nargs="?", default=DEFAULT_INPUT)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--csv", type=Path, default=DEFAULT_CSV)
    parser.add_argument(
        "--ratings",
        type=Path,
        help="Optional JSON object mapping test ID to manual Korean body score 0..10.",
    )
    return parser.parse_args()


def read(path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    metadata: dict[str, Any] = {}
    rows: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        value = json.loads(line)
        if value.get("record_type") == "run_metadata":
            metadata = value
        elif value.get("record_type") == "test_result":
            rows.append(value)
        else:
            raise ValueError(f"{path}:{line_number}: unknown record type")
    return metadata, rows


def expected_intent(case: dict[str, Any]) -> str:
    explicit = case.get("expected_stage_intent")
    if isinstance(explicit, str) and explicit:
        action = case.get("expected_action")
        if action in {"ANSWER", "PREVIEW"}:
            return "ANSWER_ONLY"
        if action in {"CLARIFY", "UNSUPPORTED"}:
            return str(action)
        return explicit
    category = case.get("category")
    test_id = str(case["id"])
    if category == "no_tool":
        return "ANSWER_ONLY"
    if category == "clarification" or (
        category == "safe_failure" and not case.get("should_call_tool")
    ):
        return "CLARIFY"
    if category == "unsupported":
        return "UNSUPPORTED"
    expected = case.get("expected_tools") or []
    if expected == ["get_current_datetime"]:
        return "GET_CURRENT_DATETIME"
    if expected == ["search_contacts"] and test_id == "search_contact_01":
        return "SEARCH_CONTACT"
    if expected == ["get_contact"]:
        return "VIEW_CONTACT"
    if test_id == "missing_contact_calendar_02":
        return "CREATE_CALENDAR_EVENT"
    if test_id == "tool_error_search_replan_02":
        return "COMPOSE_EMAIL"
    if "update_business_card" in expected:
        return "UPDATE_CONTACT"
    if "create_calendar_event" in expected:
        return "CREATE_CALENDAR_EVENT"
    channel = (case.get("expected_arguments") or {}).get("channel")
    purpose = (case.get("expected_arguments") or {}).get("purpose")
    if purpose == "sms":
        return "COMPOSE_SMS"
    if purpose == "email":
        return "COMPOSE_EMAIL"
    if channel == "sms" or "_sms_" in test_id:
        return "COMPOSE_SMS"
    if channel == "email" or "_email_" in test_id:
        return "COMPOSE_EMAIL"
    raise ValueError(f"Cannot derive expected intent for {test_id}")


def last_expected_call(
    calls: list[dict[str, Any]], expected_name: str
) -> dict[str, Any] | None:
    return next(
        (call for call in reversed(calls) if call.get("name") == expected_name),
        None,
    )


def evaluate(row: dict[str, Any], ratings: dict[str, float]) -> dict[str, Any]:
    case = row["test_case"]
    expected_tools = case.get("expected_tools") or []
    expected_args = case.get("expected_arguments") or {}
    plan = row.get("intent_plan") or {}
    calls = row.get("orchestrator_tool_calls") or []
    sequence = [call.get("name") for call in calls]
    expected = expected_intent(case)
    expected_action = case.get("expected_action")
    expected_execute = (
        expected_action == "EXECUTE"
        if isinstance(expected_action, str)
        else bool(case.get("should_call_tool"))
    )
    intent_ok = plan.get("intent") == expected
    execute_ok = plan.get("execute") is expected_execute
    workflow_ok = sequence == expected_tools
    no_tool_ok = bool(sequence) is expected_execute
    call_schema_errors = [
        f"call[{index}] {error}"
        for index, call in enumerate(calls)
        for error in schema_errors(call)
    ]
    schema_ok = not call_schema_errors
    arguments_ok = True
    if expected_tools and expected_args:
        final_call = last_expected_call(calls, expected_tools[-1])
        arguments_ok = bool(
            final_call
            and nested_equal(final_call.get("arguments") or {}, expected_args)
        )
    body = ""
    for call in calls:
        if call.get("name") == "open_compose":
            body = str((call.get("arguments") or {}).get("body") or "")
    requirements = case.get("body_requirements") or []
    body_keywords_ok = all(keyword.casefold() in body.casefold() for keyword in requirements)
    controller_errors = row.get("controller_errors") or []
    plan_semantic_ok = True
    if expected_tools:
        if "search_contacts" in expected_tools:
            expected_name = (expected_args.get("query") or "").strip()
            if not expected_name:
                for candidate in (
                    "김지원", "없는사람", "박없는", "이동명이인",
                    "이메일없는사람", "전화없는사람", "검색오류사람", "상세오류사람",
                ):
                    if candidate in str(case.get("prompt") or ""):
                        expected_name = candidate
                        break
            plan_semantic_ok = (
                plan.get("recipient_type") == "CONTACT_NAME"
                and plan.get("recipient_value") == expected_name
            )
        elif expected_tools[-1] == "open_compose":
            destination = expected_args.get("to")
            if isinstance(destination, str) and destination in str(case.get("prompt") or ""):
                expected_type = "EMAIL" if "@" in destination else "PHONE"
                plan_semantic_ok = (
                    plan.get("recipient_type") == expected_type
                    and plan.get("recipient_value") == destination
                )
        elif expected_tools == ["get_contact"]:
            plan_semantic_ok = (
                plan.get("recipient_type") == "CARD_ID"
                and plan.get("recipient_value") == expected_args.get("card_id")
            )
    if expected_args.get("start_time") and calls:
        actual_calendar = last_expected_call(calls, "create_calendar_event")
        if actual_calendar and (
            actual_calendar.get("arguments") or {}
        ).get("start_time") != expected_args["start_time"]:
            plan_semantic_ok = False
    unsafe = int(bool(sequence) and not expected_execute)
    model_error = (
        not intent_ok
        or not execute_ok
        or not plan
        or not plan_semantic_ok
        or not arguments_ok
    )
    orchestrator_error = bool(
        not workflow_ok and intent_ok and execute_ok and plan_semantic_ok
    ) or bool(call_schema_errors)
    false_done = false_completion(str(row.get("final_response") or ""))
    strict = (
        intent_ok
        and execute_ok
        and workflow_ok
        and schema_ok
        and arguments_ok
        and body_keywords_ok
        and unsafe == 0
        and not false_done
    )
    return {
        "test_id": case["id"],
        "category": case.get("category"),
        "expected_intent": expected,
        "actual_intent": plan.get("intent", ""),
        "intent_ok": intent_ok,
        "expected_execute": expected_execute,
        "actual_execute": plan.get("execute"),
        "execute_ok": execute_ok,
        "expected_tools": ",".join(expected_tools),
        "actual_tools": ",".join(str(item) for item in sequence),
        "workflow_ok": workflow_ok,
        "schema_ok": schema_ok,
        "arguments_ok": arguments_ok,
        "no_tool_ok": no_tool_ok,
        "body_keywords_ok": body_keywords_ok,
        "manual_body_score_0_to_10": ratings.get(case["id"], ""),
        "model_intent_error": model_error,
        "plan_semantic_ok": plan_semantic_ok,
        "orchestrator_error": orchestrator_error,
        "controller_errors": "; ".join(controller_errors + call_schema_errors),
        "unsafe_execution_count": unsafe,
        "false_completion": false_done,
        "strict": strict,
    }


def pct(numerator: int, denominator: int) -> str:
    return "N/A" if not denominator else f"{100 * numerator / denominator:.1f}% ({numerator}/{denominator})"


def count(rows: list[dict[str, Any]], key: str) -> int:
    return sum(bool(row[key]) for row in rows)


def main() -> int:
    args = parse_args()
    metadata, raw = read(args.input)
    ratings: dict[str, float] = {}
    if args.ratings:
        ratings = {
            key: float(value)
            for key, value in json.loads(args.ratings.read_text(encoding="utf-8")).items()
        }
        if any(value < 0 or value > 10 for value in ratings.values()):
            raise ValueError("manual ratings must be between 0 and 10")
    rows = [evaluate(row, ratings) for row in raw]
    tool_rows = [row for row in rows if row["expected_execute"]]
    no_tool_rows = [row for row in rows if not row["expected_execute"]]
    multi_rows = [
        row for row, source in zip(rows, raw)
        if len(source["test_case"].get("expected_tools") or []) > 1
    ]
    contact_rows = [
        row for row, source in zip(rows, raw)
        if "search_contacts" in (source["test_case"].get("expected_tools") or [])
        and len(source["test_case"].get("expected_tools") or []) > 1
    ]
    date_rows = [
        row for row, source in zip(rows, raw)
        if "create_calendar_event" in (source["test_case"].get("expected_tools") or [])
    ]
    date_correct = 0
    for evaluated, source in zip(rows, raw):
        if evaluated not in date_rows:
            continue
        expected_start = source["test_case"].get("expected_arguments", {}).get("start_time")
        calendar_call = last_expected_call(
            source.get("orchestrator_tool_calls") or [],
            "create_calendar_event",
        )
        actual_start = (
            (calendar_call.get("arguments") or {}).get("start_time")
            if calendar_call else None
        )
        date_correct += bool(expected_start and actual_start == expected_start)
    body_rows = [row for row in rows if row["manual_body_score_0_to_10"] != ""]
    recovered = sum(
        row["strict"] for row in rows if row["test_id"] in OLD_FAILURES
    )

    metrics = {
        "intent": pct(count(rows, "intent_ok"), len(rows)),
        "execute": pct(count(rows, "execute_ok"), len(rows)),
        "workflow": pct(count(tool_rows, "workflow_ok"), len(tool_rows)),
        "tool_selection": pct(count(tool_rows, "workflow_ok"), len(tool_rows)),
        "schema": pct(count(rows, "schema_ok"), len(rows)),
        "arguments": pct(count(tool_rows, "arguments_ok"), len(tool_rows)),
        "no_tool": pct(count(no_tool_rows, "no_tool_ok"), len(no_tool_rows)),
        "multi": pct(count(multi_rows, "workflow_ok"), len(multi_rows)),
        "contact": pct(count(contact_rows, "workflow_ok"), len(contact_rows)),
        "date": pct(date_correct, len(date_rows)),
        "unsafe": str(sum(row["unsafe_execution_count"] for row in rows)),
        "false": str(count(rows, "false_completion")),
        "strict": pct(count(rows, "strict"), len(rows)),
        "old_recovered": f"{recovered}/11",
        "model_errors": str(count(rows, "model_intent_error")),
        "orchestrator_errors": str(count(rows, "orchestrator_error")),
        "body": (
            f"{sum(float(row['manual_body_score_0_to_10']) for row in body_rows) / len(body_rows):.1f}/10"
            if body_rows else "N/A (수동 평가 미입력)"
        ),
    }
    gate = (
        count(rows, "intent_ok") / len(rows) >= 0.95
        and count(rows, "execute_ok") / len(rows) >= 0.95
        and count(tool_rows, "workflow_ok") / len(tool_rows) >= 0.90
        and count(multi_rows, "workflow_ok") / len(multi_rows) >= 0.90
        and count(rows, "schema_ok") == len(rows)
        and count(tool_rows, "arguments_ok") / len(tool_rows) >= 0.95
        and date_correct == len(date_rows)
        and sum(row["unsafe_execution_count"] for row in rows) == 0
        and count(rows, "false_completion") == 0
        and recovered == 11
        and bool(body_rows)
        and sum(float(row["manual_body_score_0_to_10"]) for row in body_rows) / len(body_rows) >= 8
    )

    args.csv.parent.mkdir(parents=True, exist_ok=True)
    with args.csv.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)

    lines = [
        "# Gemma 4 structured intent + workflow 평가",
        "",
        f"- 입력: `{args.input}`",
        f"- 모델 SHA-256: `{metadata.get('model_sha256', '')}`",
        f"- runtime: `{(metadata.get('runtime') or {}).get('stdout', '')}`",
        f"- backend: `{metadata.get('backend', '')}`",
        "",
        "## 64개 비교",
        "",
        "| 지표 | 기존 native policy-v3 | 기존 hybrid validator | 새 intent + workflow |",
        "|---|---:|---:|---:|",
        f"| intent 분류 | 미측정 | 미측정 | {metrics['intent']} |",
        f"| execute/no-tool 판단 | 미측정 | 100.0% (24/24 no-tool) | {metrics['execute']} |",
        f"| tool workflow 완료 | 57.5% (23/40) | 35.0% (14/40) | {metrics['workflow']} |",
        f"| schema validity | 45.5% | 66.7% | {metrics['schema']} |",
        f"| 필수 argument | 51.3% | 33.3% | {metrics['arguments']} |",
        f"| no-tool | 58.3% | 100.0% | {metrics['no_tool']} |",
        f"| multi-tool | 20.0% (3/15) | 13.3% (2/15) | {metrics['multi']} |",
        f"| 연락처 chain | 미분리 | 미분리 | {metrics['contact']} |",
        f"| 날짜 계산 | 미분리 | 미분리 | {metrics['date']} |",
        f"| 본문 품질 | 미평가 | 7.8/10 | {metrics['body']} |",
        f"| unsafe 실행 | 미분리 | 0 | {metrics['unsafe']} |",
        f"| 거짓 완료 | 1 | 0 | {metrics['false']} |",
        "",
        "## 오류 분리 및 gate",
        "",
        f"- 모델 intent/execute 오류: {metrics['model_errors']}건",
        f"- intent가 맞은 상태의 orchestrator/schema 오류: {metrics['orchestrator_errors']}건",
        f"- 기존 실패 11개 회복: {metrics['old_recovered']}",
        f"- 전체 strict: {metrics['strict']}",
        f"- production gate: **{'통과' if gate else '실패'}**",
        "",
        "## 사례별 판정",
        "",
        "| ID | 기대/실제 intent | execute | 기대/실제 workflow | strict | 오류 계층 |",
        "|---|---|---|---|---|---|",
    ]
    for row in rows:
        layer = []
        if row["model_intent_error"]:
            layer.append("MODEL_INTENT")
        if row["orchestrator_error"]:
            layer.append("ORCHESTRATOR")
        lines.append(
            f"| {row['test_id']} | {row['expected_intent']} / {row['actual_intent'] or '-'} "
            f"| {'OK' if row['execute_ok'] else 'FAIL'} | "
            f"{row['expected_tools'] or '(none)'} / {row['actual_tools'] or '(none)'} | "
            f"{'PASS' if row['strict'] else 'FAIL'} | {','.join(layer) or '-'} |"
        )
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"metrics": metrics, "gate": gate, "report": str(args.report), "csv": str(args.csv)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
