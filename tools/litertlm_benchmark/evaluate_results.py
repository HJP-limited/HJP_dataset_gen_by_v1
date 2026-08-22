#!/usr/bin/env python3
"""Evaluate HJP LiteRT-LM JSONL runs and emit Markdown plus editable CSV."""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path
from typing import Any


BENCHMARK_DIR = Path(__file__).resolve().parent
DEFAULT_RESULTS = BENCHMARK_DIR / "results"
DEFAULT_REPORTS = BENCHMARK_DIR / "reports"
KNOWN_TOOLS = {
    "search_contacts",
    "get_contact",
    "update_business_card",
    "create_calendar_event",
    "open_compose",
    "get_current_datetime",
    "compose_email",
    "compose_sms",
}
MODEL_TO_EXECUTION_TOOL = {
    "compose_email": "open_compose",
    "compose_sms": "open_compose",
}
EMAIL_RE = re.compile(
    r"(?i)\b[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@"
    r"[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?"
    r"(?:\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+(?![A-Z0-9.-])"
)
PHONE_RE = re.compile(
    r"(?<!\d)(?:\+82[- ]?|0)\d{1,2}[- ]?\d{3,4}[- ]?\d{4}(?!\d)"
)
LOCAL_DATETIME_RE = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$")
UPDATABLE_FIELDS = {
    "name",
    "name_en",
    "company",
    "department",
    "title",
    "industry",
    "location",
    "phone",
    "mobile",
    "email",
    "address",
    "website",
    "memo",
}
TOOL_SCHEMAS: dict[str, dict[str, Any]] = {
    "search_contacts": {
        "required": {"query"},
        "properties": {"query": "string", "limit": "integer"},
    },
    "get_contact": {
        "required": {"card_id"},
        "properties": {"card_id": "string", "purpose": "string"},
        "enums": {"purpose": {"display", "email", "sms", "calendar"}},
    },
    "update_business_card": {
        "required": {"card_id"},
        "properties": {
            "card_id": "string",
            "updates": "object",
            "clear_fields": "array",
        },
    },
    "create_calendar_event": {
        "required": {"title", "start_time"},
        "properties": {
            "title": "string",
            "start_time": "string",
            "end_time": "string",
            "location": "string",
            "description": "string",
            "attendee_emails": "array",
        },
    },
    "open_compose": {
        "required": {"channel", "to", "body"},
        "properties": {
            "channel": "string",
            "to": "string",
            "subject": "string",
            "body": "string",
        },
        "enums": {"channel": {"email", "sms"}},
    },
    "compose_email": {
        "required": {"to", "subject", "body"},
        "properties": {
            "to": "string",
            "subject": "string",
            "body": "string",
        },
    },
    "compose_sms": {
        "required": {"to", "body"},
        "properties": {
            "to": "string",
            "body": "string",
        },
    },
    "get_current_datetime": {
        "required": set(),
        "properties": {"timezone": "string"},
    },
}
FALSE_COMPLETION_RE = re.compile(
    r"(메일|이메일|문자).{0,12}(보냈|전송(?:이)? 완료|전송했습니다)"
    r"|일정.{0,12}(저장|등록|생성)(?:이)? 완료"
    r"|명함.{0,24}(수정|저장|필드를 비우는 작업).{0,12}(완료|되었습니다)"
)
NEGATION_RE = re.compile(
    r"(않았|않습니다|아닙니다|못했|열지 않았|전송하지|저장하지|"
    r"수정하지|완료된 것이 아니)"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Evaluate one or more run_benchmark.py JSONL result files."
    )
    parser.add_argument(
        "inputs",
        nargs="*",
        type=Path,
        help="Result JSONL files (default: newest file in results/)",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_REPORTS,
        help="Markdown/CSV destination",
    )
    return parser.parse_args()


def newest_result() -> Path:
    candidates = sorted(
        DEFAULT_RESULTS.glob("*.jsonl"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        raise ValueError(f"No JSONL result found in {DEFAULT_RESULTS}")
    return candidates[0]


def read_result(
    path: Path,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    metadata: dict[str, Any] = {}
    records: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_number}: {exc}") from exc
            if value.get("record_type") == "run_metadata":
                metadata = value
            elif value.get("record_type") == "test_result":
                records.append(value)
    if not records:
        raise ValueError(f"{path}: no test_result records")
    return metadata, records


def matches_type(value: Any, expected: str) -> bool:
    if expected == "string":
        return isinstance(value, str)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "object":
        return isinstance(value, dict)
    if expected == "array":
        return isinstance(value, list)
    return False


def schema_errors(call: dict[str, Any]) -> list[str]:
    name = call.get("name")
    if name not in TOOL_SCHEMAS:
        return [f"unknown tool: {name!r}"]
    arguments = call.get("arguments")
    if not isinstance(arguments, dict):
        return ["arguments is not an object"]
    schema = TOOL_SCHEMAS[name]
    errors = []
    missing = schema["required"] - set(arguments)
    if missing:
        errors.append(f"missing: {', '.join(sorted(missing))}")
    unknown = set(arguments) - set(schema["properties"])
    if unknown:
        errors.append(f"unknown arguments: {', '.join(sorted(unknown))}")
    for key, value in arguments.items():
        expected_type = schema["properties"].get(key)
        if expected_type and not matches_type(value, expected_type):
            errors.append(f"{key}: expected {expected_type}")
    for key, allowed in schema.get("enums", {}).items():
        if key in arguments and arguments[key] not in allowed:
            errors.append(f"{key}: invalid enum {arguments[key]!r}")

    if name == "search_contacts" and "limit" in arguments:
        limit = arguments["limit"]
        if (
            isinstance(limit, int)
            and not isinstance(limit, bool)
            and not 1 <= limit <= 10
        ):
            errors.append("limit: expected 1..10")
    if name == "update_business_card":
        updates = arguments.get("updates")
        if isinstance(updates, dict):
            bad = set(updates) - UPDATABLE_FIELDS
            if bad:
                errors.append(
                    f"updates: unknown fields {', '.join(sorted(bad))}"
                )
            for key, value in updates.items():
                if key in UPDATABLE_FIELDS and not isinstance(value, str):
                    errors.append(f"updates.{key}: expected string")
        clear_fields = arguments.get("clear_fields")
        if isinstance(clear_fields, list):
            if any(
                not isinstance(item, str) or item not in UPDATABLE_FIELDS
                for item in clear_fields
            ):
                errors.append("clear_fields: invalid field")
    if name == "create_calendar_event":
        start_time = arguments.get("start_time")
        if isinstance(start_time, str) and not LOCAL_DATETIME_RE.fullmatch(
            start_time
        ):
            errors.append("start_time: expected yyyy-MM-dd'T'HH:mm")
        attendees = arguments.get("attendee_emails")
        if isinstance(attendees, list) and any(
            not isinstance(item, str) or EMAIL_RE.fullmatch(item) is None
            for item in attendees
        ):
            errors.append("attendee_emails: expected valid emails")
    if name in {"open_compose", "compose_email", "compose_sms"}:
        channel = arguments.get("channel")
        if name == "compose_email":
            channel = "email"
        elif name == "compose_sms":
            channel = "sms"
        to = arguments.get("to")
        body = arguments.get("body")
        subject = arguments.get("subject")
        if not isinstance(to, str) or not to.strip():
            errors.append("to: expected non-empty string")
        if not isinstance(body, str) or not body.strip():
            errors.append("body: expected non-empty string")
        if channel == "email":
            if not isinstance(to, str) or EMAIL_RE.fullmatch(to) is None:
                errors.append("to: expected valid email")
            if not isinstance(subject, str) or not subject.strip():
                errors.append("subject: expected non-empty email subject")
        elif channel == "sms":
            if not isinstance(to, str) or PHONE_RE.fullmatch(to) is None:
                errors.append("to: expected valid phone")
            if isinstance(subject, str) and subject.strip():
                errors.append("subject: forbidden for sms")
    return errors


def nested_equal(actual: Any, expected: Any) -> bool:
    if isinstance(expected, dict):
        return isinstance(actual, dict) and all(
            key in actual and nested_equal(actual[key], value)
            for key, value in expected.items()
        )
    if isinstance(expected, list):
        return isinstance(actual, list) and actual == expected
    return actual == expected


def is_ordered_subsequence(expected: list[str], actual: list[str]) -> bool:
    position = 0
    for name in actual:
        if position < len(expected) and name == expected[position]:
            position += 1
    return position == len(expected)


def false_completion(text: str) -> bool:
    for match in FALSE_COMPLETION_RE.finditer(text):
        start = max(0, match.start() - 25)
        end = min(len(text), match.end() + 15)
        if not NEGATION_RE.search(text[start:end]):
            return True
    return False


def ratio(numerator: int, denominator: int) -> str:
    if denominator == 0:
        return "N/A"
    return f"{numerator / denominator * 100:.1f}% ({numerator}/{denominator})"


def canonical_call(call: dict[str, Any]) -> dict[str, Any]:
    name = str(call.get("name"))
    arguments = call.get("arguments")
    if not isinstance(arguments, dict):
        return call
    if name == "compose_email":
        return {**call, "name": "open_compose", "arguments": {
            "channel": "email",
            **arguments,
        }}
    if name == "compose_sms":
        return {**call, "name": "open_compose", "arguments": {
            "channel": "sms",
            **arguments,
        }}
    return call


def policy_traces(record: dict[str, Any]) -> list[dict[str, Any]]:
    traces = []
    for response in record.get("tool_responses") or []:
        if not isinstance(response, dict):
            continue
        trace = response.get("policy_trace")
        if isinstance(trace, dict):
            traces.append(trace)
    return traces


def executed_calls_from_traces(
    traces: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    calls = []
    for trace in traces:
        if trace.get("tool_executed") is not True:
            continue
        name = trace.get("actual_tool_name")
        arguments = trace.get("actual_arguments")
        calls.append({
            "name": name,
            "arguments": arguments if isinstance(arguments, dict) else {},
        })
    return calls


def evaluate_record(record: dict[str, Any]) -> dict[str, Any]:
    case = record.get("test_case") or {}
    raw_calls = record.get("tool_calls") or []
    canonical_calls = [canonical_call(call) for call in raw_calls]
    traces = policy_traces(record)
    executed_calls = executed_calls_from_traces(traces)
    calls = executed_calls if traces else canonical_calls
    raw_names = [str(call.get("name")) for call in raw_calls]
    canonical_raw_names = [
        str(call.get("name")) for call in canonical_calls
    ]
    names = [str(call.get("name")) for call in calls]
    expected_names = list(case.get("expected_tools") or [])
    should_call = bool(case.get("should_call_tool"))
    expected_arguments = case.get("expected_arguments") or {}
    native = bool(raw_calls)
    runtime_error = bool(
        record.get("runtime_error_detected")
        or "An error occurred" in str(record.get("stdout") or "")
        or "Traceback (most recent call last):"
        in str(record.get("stderr") or "")
    )
    process_success = (
        record.get("return_code") == 0
        and not record.get("timed_out")
        and not record.get("launch_error")
        and not runtime_error
    )
    per_call_schema_errors = [schema_errors(call) for call in raw_calls]
    schema_success = (
        native
        and not record.get("event_parse_errors")
        and all(not errors for errors in per_call_schema_errors)
    )
    selected = names == expected_names
    raw_selected = canonical_raw_names == expected_names
    target_arguments = calls[-1].get("arguments") if calls else None
    required_arguments_ok = (
        bool(calls)
        and isinstance(target_arguments, dict)
        and nested_equal(target_arguments, expected_arguments)
    )
    no_tool_ok = not calls if not should_call else None
    raw_no_tool_ok = not native if not should_call else None
    multi_tool = len(expected_names) > 1
    chain_complete = (
        is_ordered_subsequence(expected_names, names)
        and required_arguments_ok
        and all(name in KNOWN_TOOLS for name in names)
        if multi_tool
        else None
    )
    unknown_count = sum(name not in KNOWN_TOOLS for name in raw_names)

    compose_calls = [
        call for call in calls if call.get("name") == "open_compose"
    ]
    bodies = []
    for call in compose_calls:
        arguments = call.get("arguments")
        bodies.append(
            arguments.get("body") if isinstance(arguments, dict) else None
        )
    empty_body_count = sum(
        not isinstance(body, str) or not body.strip() for body in bodies
    )
    requirements = list(case.get("body_requirements") or [])
    body_text = "\n".join(body for body in bodies if isinstance(body, str))
    keyword_hits = sum(keyword in body_text for keyword in requirements)
    keyword_ok = (
        keyword_hits == len(requirements) if requirements else None
    )
    output = str(record.get("assistant_output") or "")

    detail_errors = []
    for index, errors in enumerate(per_call_schema_errors):
        detail_errors.extend(f"call[{index}] {error}" for error in errors)
    detail_errors.extend(
        f"event line {error.get('line')}: {error.get('error')}"
        for error in record.get("event_parse_errors") or []
    )
    if record.get("launch_error"):
        detail_errors.append(str(record["launch_error"]))
    if record.get("timed_out"):
        detail_errors.append("process timeout")
    elif runtime_error:
        detail_errors.append(
            "CLI reported a runtime error despite its process return code"
        )
    elif record.get("return_code") not in (0, None):
        detail_errors.append(f"return code {record.get('return_code')}")
    stderr = str(record.get("stderr") or "")
    if runtime_error and stderr.strip():
        detail_errors.append(stderr.strip().splitlines()[-1])

    return {
        "test_id": record.get("test_id"),
        "category": record.get("category"),
        "prompt": record.get("prompt"),
        "process_success": process_success,
        "return_code": record.get("return_code"),
        "timed_out": bool(record.get("timed_out")),
        "duration_seconds": record.get("duration_seconds"),
        "native_tool_call": native,
        "expected_tools": expected_names,
        "raw_model_tools": raw_names,
        "canonical_raw_tools": canonical_raw_names,
        "actual_tools": names,
        "tool_selection_ok": selected,
        "raw_tool_selection_ok": raw_selected,
        "schema_parse_ok": schema_success,
        "required_arguments_ok": required_arguments_ok
        if should_call and expected_arguments
        else None,
        "no_tool_ok": no_tool_ok,
        "raw_no_tool_ok": raw_no_tool_ok,
        "multi_tool_chain_complete": chain_complete,
        "unknown_tool_count": unknown_count,
        "policy_rejection_count": sum(
            trace.get("validation_result") == "rejected"
            for trace in traces
        ),
        "policy_reject_reasons": [
            str(trace.get("reject_reason"))
            for trace in traces
            if trace.get("validation_result") == "rejected"
        ],
        "workflow_violation_execution_count": sum(
            trace.get("validation_result") != "approved"
            and trace.get("tool_executed") is True
            for trace in traces
        ),
        "false_completion": false_completion(output),
        "empty_body_count": empty_body_count,
        "body_keyword_hits": keyword_hits,
        "body_keyword_total": len(requirements),
        "body_keywords_ok": keyword_ok,
        "errors": detail_errors,
        "assistant_output": output,
        "raw_stdout": str(record.get("stdout") or ""),
        "raw_stderr": stderr,
    }


def aggregate(rows: list[dict[str, Any]]) -> dict[str, str | int]:
    tool_expected = [
        row for row in rows if row["expected_tools"]
    ]
    argument_expected = [
        row for row in rows if row["required_arguments_ok"] is not None
    ]
    no_tool = [row for row in rows if row["no_tool_ok"] is not None]
    multi_tool = [
        row
        for row in rows
        if row["multi_tool_chain_complete"] is not None
    ]
    native_calls = [row for row in rows if row["native_tool_call"]]
    keyword_rows = [
        row for row in rows if row["body_keyword_total"] > 0
    ]
    keyword_hits = sum(row["body_keyword_hits"] for row in rows)
    keyword_total = sum(row["body_keyword_total"] for row in rows)
    return {
        "process_success_rate": ratio(
            sum(row["process_success"] for row in rows), len(rows)
        ),
        "native_tool_call_rate": ratio(
            sum(row["native_tool_call"] for row in rows), len(rows)
        ),
        "tool_selection_accuracy": ratio(
            sum(row["tool_selection_ok"] for row in tool_expected),
            len(tool_expected),
        ),
        "raw_tool_selection_accuracy": ratio(
            sum(row["raw_tool_selection_ok"] for row in tool_expected),
            len(tool_expected),
        ),
        "required_argument_accuracy": ratio(
            sum(row["required_arguments_ok"] for row in argument_expected),
            len(argument_expected),
        ),
        "schema_parse_success_rate": ratio(
            sum(row["schema_parse_ok"] for row in native_calls),
            len(native_calls),
        ),
        "no_tool_accuracy": ratio(
            sum(row["no_tool_ok"] for row in no_tool), len(no_tool)
        ),
        "raw_no_tool_accuracy": ratio(
            sum(row["raw_no_tool_ok"] for row in no_tool), len(no_tool)
        ),
        "multi_tool_chain_completion_rate": ratio(
            sum(row["multi_tool_chain_complete"] for row in multi_tool),
            len(multi_tool),
        ),
        "unknown_tool_count": sum(row["unknown_tool_count"] for row in rows),
        "policy_rejection_count": sum(
            row["policy_rejection_count"] for row in rows
        ),
        "workflow_violation_execution_count": sum(
            row["workflow_violation_execution_count"] for row in rows
        ),
        "false_completion_count": sum(
            row["false_completion"] for row in rows
        ),
        "empty_body_count": sum(row["empty_body_count"] for row in rows),
        "body_keyword_reflection_rate": ratio(keyword_hits, keyword_total),
        "body_keyword_case_success": ratio(
            sum(row["body_keywords_ok"] for row in keyword_rows),
            len(keyword_rows),
        ),
    }


def csv_value(value: Any) -> Any:
    if isinstance(value, (list, dict)):
        return json.dumps(value, ensure_ascii=False)
    if value is None:
        return ""
    return value


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    fields = [
        "test_id",
        "category",
        "process_success",
        "return_code",
        "timed_out",
        "duration_seconds",
        "native_tool_call",
        "expected_tools",
        "actual_tools",
        "raw_model_tools",
        "tool_selection_ok",
        "raw_tool_selection_ok",
        "schema_parse_ok",
        "required_arguments_ok",
        "no_tool_ok",
        "raw_no_tool_ok",
        "multi_tool_chain_complete",
        "unknown_tool_count",
        "policy_rejection_count",
        "policy_reject_reasons",
        "workflow_violation_execution_count",
        "false_completion",
        "empty_body_count",
        "body_keyword_hits",
        "body_keyword_total",
        "body_keywords_ok",
        "manual_body_quality_0_10",
        "manual_notes",
        "errors",
        "assistant_output",
        "prompt",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            output = {key: csv_value(row.get(key)) for key in fields}
            output["manual_body_quality_0_10"] = ""
            output["manual_notes"] = ""
            writer.writerow(output)


def markdown_escape(value: Any) -> str:
    return str(value).replace("|", "\\|").replace("\n", "<br>")


def write_markdown(
    path: Path,
    input_path: Path,
    metadata: dict[str, Any],
    rows: list[dict[str, Any]],
    metrics: dict[str, str | int],
    csv_path: Path,
) -> None:
    model = metadata.get("model_name", "unknown")
    lines = [
        f"# LiteRT-LM 벤치마크 보고서: {model}",
        "",
        "## 실행 정보",
        "",
        f"- 결과 원본: `{input_path.resolve()}`",
        f"- 수동 평가 CSV: `{csv_path.resolve()}`",
        f"- 모델: `{metadata.get('model_path', '')}`",
        f"- 모델 SHA-256: `{metadata.get('model_sha256', '')}`",
        f"- backend: `{metadata.get('backend', '')}`",
        f"- CLI run options: `{json.dumps(metadata.get('cli_run_options', {}), ensure_ascii=False)}`",
        f"- CLI: `{(metadata.get('cli') or {}).get('stdout', '')}`",
        f"- 실행 시각(UTC): `{metadata.get('run_started_at_utc', '')}`",
        "",
        "## 자동 평가",
        "",
        "| 항목 | 결과 |",
        "|---|---:|",
    ]
    labels = {
        "process_success_rate": "프로세스 실행 성공률",
        "native_tool_call_rate": "native `[tool_call]` 출력률",
        "tool_selection_accuracy": "실제 실행 tool 선택 정확도",
        "raw_tool_selection_accuracy": "raw model tool 선택 정확도",
        "required_argument_accuracy": "필수 argument 정확도",
        "schema_parse_success_rate": "JSON/schema 파싱 성공률",
        "no_tool_accuracy": "정책 적용 no-tool 정확도",
        "raw_no_tool_accuracy": "raw model no-tool 정확도",
        "multi_tool_chain_completion_rate": "multi-tool chain 완료율",
        "unknown_tool_count": "존재하지 않는 tool 생성 횟수",
        "policy_rejection_count": "정책 차단 횟수",
        "workflow_violation_execution_count": "workflow 위반 실행 횟수",
        "false_completion_count": "거짓 완료 표현 횟수",
        "empty_body_count": "비어 있는 body 횟수",
        "body_keyword_reflection_rate": "body 요구 키워드 반영률",
        "body_keyword_case_success": "body 요구사항 전체 충족 케이스",
    }
    for key, label in labels.items():
        lines.append(f"| {label} | {metrics[key]} |")

    lines.extend(
        [
            "",
            "native tool call은 stdout에 LiteRT-LM CLI가 출력한 "
            "`[tool_call]` 표식과 파싱 가능한 event가 있을 때만 인정한다. "
            "일반 텍스트에 JSON처럼 보이는 문자열만 있으면 실패다.",
            "",
            "## 케이스별 결과",
            "",
            "| ID | 실행 | native | 예상 tool | raw model tool | 실제 실행 tool | schema | 오류 |",
            "|---|---:|---:|---|---|---|---:|---|",
        ]
    )
    for row in rows:
        errors = "; ".join(row["errors"])
        lines.append(
            "| {id} | {run} | {native} | {expected} | {raw} | {actual} | "
            "{schema} | {errors} |".format(
                id=markdown_escape(row["test_id"]),
                run="OK" if row["process_success"] else "FAIL",
                native="YES" if row["native_tool_call"] else "NO",
                expected=markdown_escape(
                    ", ".join(row["expected_tools"]) or "(none)"
                ),
                raw=markdown_escape(
                    ", ".join(row["raw_model_tools"]) or "(none)"
                ),
                actual=markdown_escape(
                    ", ".join(row["actual_tools"]) or "(none)"
                ),
                schema="OK" if row["schema_parse_ok"] else "FAIL",
                errors=markdown_escape(errors),
            )
        )

    lines.extend(
        [
            "",
            "## 사람 본문 품질 평가(0~10점)",
            "",
            "자연스러운 한국어 품질은 자동 점수로 단정하지 않는다. "
            "생성된 CSV의 `manual_body_quality_0_10`, `manual_notes` 열을 채운다.",
            "",
            "| ID | 본문 품질(0~10) | 메모 |",
            "|---|---:|---|",
        ]
    )
    for row in rows:
        lines.append(
            f"| {markdown_escape(row['test_id'])} |  |  |"
        )
    failed_rows = [row for row in rows if not row["process_success"]]
    if failed_rows:
        lines.extend(["", "## 실행 오류 원문", ""])
        for row in failed_rows:
            lines.extend(
                [
                    f"### {row['test_id']}",
                    "",
                    "stdout:",
                    "",
                    "```text",
                    row["raw_stdout"].rstrip(),
                    "```",
                    "",
                    "stderr:",
                    "",
                    "```text",
                    row["raw_stderr"].rstrip(),
                    "```",
                    "",
                ]
            )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def evaluate_file(input_path: Path, output_dir: Path) -> tuple[Path, Path]:
    metadata, records = read_result(input_path)
    rows = [evaluate_record(record) for record in records]
    metrics = aggregate(rows)
    output_dir.mkdir(parents=True, exist_ok=True)
    stem = input_path.stem
    csv_path = output_dir / f"{stem}_evaluation.csv"
    report_path = output_dir / f"{stem}_report.md"
    write_csv(csv_path, rows)
    write_markdown(
        report_path, input_path, metadata, rows, metrics, csv_path
    )
    return report_path, csv_path


def main() -> int:
    args = parse_args()
    try:
        inputs = args.inputs or [newest_result()]
        output_dir = args.output_dir.expanduser().resolve()
        for raw_input in inputs:
            input_path = raw_input.expanduser().resolve()
            report_path, csv_path = evaluate_file(input_path, output_dir)
            print(f"REPORT_FILE={report_path.resolve()}")
            print(f"CSV_FILE={csv_path.resolve()}")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
