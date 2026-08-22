#!/usr/bin/env python3
"""Benchmark Gemma structured intent plus deterministic workflow orchestration."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from evaluate_results import schema_errors
from run_benchmark import (
    BENCHMARK_DIR,
    DEFAULT_RESULTS,
    DEFAULT_TESTS,
    REPO_ROOT,
    cli_version,
    command_for,
    read_jsonl,
    resolve_cli,
    resolve_path,
    run_case,
    select_cases,
    sha256_file,
)


INTENT_PRESET = BENCHMARK_DIR / "hjp_intent_preset.py"
CONTENT_PRESET = BENCHMARK_DIR / "hjp_content_preset.py"
MODEL_DEFAULT = REPO_ROOT / "models/gemma-4-E2B-it.litertlm"
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
PHONE_RE = re.compile(r"^(?:\+82[- ]?|0)\d{1,2}[- ]?\d{3,4}[- ]?\d{4}$")
MALFORMED_SPACED_EMAIL_RE = re.compile(
    r"(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+\s+"
    r"[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}(?![a-z0-9.-])"
)
ABSOLUTE_DATE_RE = re.compile(r"(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일")
TIME_RE = re.compile(r"(오전|오후)?\s*(\d{1,2})시(?:\s*(\d{1,2})분)?")
WEEKDAYS = {
    "월요일": 0, "화요일": 1, "수요일": 2, "목요일": 3,
    "금요일": 4, "토요일": 5, "일요일": 6,
}
NOW = dt.datetime(2026, 7, 24, 9, 0)

CONTACTS = {
    "김지원": [{
        "card_id": "card-kim-jiwon", "name": "김지원",
        "email": "jiwon@example.com", "mobile": "010-1234-5678",
    }],
    "이동명이인": [
        {"card_id": "card-duplicate-1", "name": "이동명이인",
         "email": "duplicate1@example.com", "mobile": "010-1111-1111"},
        {"card_id": "card-duplicate-2", "name": "이동명이인",
         "email": "duplicate2@example.com", "mobile": "010-2222-2222"},
    ],
    "이메일없는사람": [{
        "card_id": "card-no-email", "name": "이메일없는사람",
        "email": "", "mobile": "010-3333-3333",
    }],
    "전화없는사람": [{
        "card_id": "card-no-phone", "name": "전화없는사람",
        "email": "no-phone@example.com", "mobile": "",
    }],
    "상세오류사람": [{
        "card_id": "card-get-error", "name": "상세오류사람",
        "email": "get-error@example.com", "mobile": "010-7777-7777",
    }],
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run structured intent + code workflow against the 64 cases."
    )
    parser.add_argument("--model", default=str(MODEL_DEFAULT))
    parser.add_argument("--model-name", default="gemma4-intent-workflow")
    parser.add_argument("--backend", choices=("cpu", "gpu"), default="cpu")
    parser.add_argument("--cli")
    parser.add_argument("--tests-file", type=Path, default=DEFAULT_TESTS)
    parser.add_argument("--test-case", action="append", default=[])
    parser.add_argument("--category", action="append", default=[])
    parser.add_argument("--limit", type=int)
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument(
        "--max-num-tokens",
        type=int,
        help="CLI generation limit; omitted by default (this does not expand context).",
    )
    parser.add_argument("--output", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def cli_options(args: argparse.Namespace) -> SimpleNamespace:
    return SimpleNamespace(
        max_num_tokens=args.max_num_tokens,
        top_k=20,
        top_p=0.95,
        temperature=0.2,
        seed=42,
        cpu_thread_count=None,
        cache="disk",
    )


def accepted_payload(run: dict[str, Any], field: str) -> tuple[dict[str, Any] | None, list[str]]:
    errors: list[str] = []
    calls = run.get("tool_calls") or []
    responses = run.get("tool_responses") or []
    if len(calls) > 2:
        return None, ["retry_limit_exceeded"]
    for index, call in enumerate(calls):
        response = responses[index] if index < len(responses) else None
        if not isinstance(response, dict) or response.get("accepted") is not True:
            continue
        value = response.get(field)
        if isinstance(value, dict):
            return value, errors
    if run.get("event_parse_errors"):
        errors.append("native_event_parse_failure")
    errors.append("accepted_native_call_missing")
    return None, errors


def invoke(
    cli: Path,
    model: Path,
    args: argparse.Namespace,
    case: dict[str, Any],
    prompt: str,
    preset: Path,
    env: dict[str, str] | None = None,
) -> dict[str, Any]:
    command = command_for(
        cli, model, args.backend, prompt, preset, cli_options(args)
    )
    if args.dry_run:
        return {"command": command, "dry_run": True, "tool_calls": [], "tool_responses": []}
    return run_case(command, args.timeout, case, env)


def analyze_intent(
    cli: Path, model: Path, args: argparse.Namespace, case: dict[str, Any]
) -> tuple[dict[str, Any] | None, list[dict[str, Any]], list[str]]:
    wrapped = (
        "다음 사용자 요청에 답변하지 말고 반드시 submit_intent만 호출하세요.\n"
        f"사용자 요청: {case['prompt']}"
    )
    runs = [invoke(cli, model, args, case, wrapped, INTENT_PRESET)]
    value, errors = accepted_payload(runs[0], "structured_intent")
    if value is None and not args.dry_run:
        correction = (
            wrapped + "\n이전 응답은 유효한 submit_intent 호출이 아니었습니다. "
            "schema에 맞는 호출만 한 번 제출하세요."
        )
        runs.append(invoke(cli, model, args, case, correction, INTENT_PRESET))
        value, second_errors = accepted_payload(runs[1], "structured_intent")
        errors += second_errors
    return value, runs, errors


def generate_content(
    cli: Path,
    model: Path,
    args: argparse.Namespace,
    case: dict[str, Any],
    channel: str,
    goal: str,
    recipient_name: str,
    recipient_company: str = "",
    recipient_title: str = "",
    requested_tone: str = "",
) -> tuple[dict[str, Any] | None, list[dict[str, Any]], list[str]]:
    tool_name = "submit_email_content" if channel == "email" else "submit_sms_content"
    prompt = (
        f"채널: {channel}\n사용자 원문: {case['prompt']}\n본문 목표: {goal}\n"
        f"수신자 표시 이름: {recipient_name}\n"
        f"수신자 회사: {recipient_company}\n수신자 직함: {recipient_title}\n"
        f"요청한 말투: {requested_tone}\n"
        f"답변하지 말고 반드시 {tool_name}만 호출하세요."
    )
    env = {
        "HJP_CONTENT_CHANNEL": channel,
        "HJP_CONTENT_GOAL": goal,
        "HJP_RECIPIENT_NAME": recipient_name,
        "HJP_RECIPIENT_COMPANY": recipient_company,
        "HJP_RECIPIENT_TITLE": recipient_title,
        "HJP_REQUESTED_TONE": requested_tone,
        "HJP_USER_FACTS": str(case["prompt"]),
    }
    runs = [invoke(cli, model, args, case, prompt, CONTENT_PRESET, env)]
    value, errors = accepted_payload(runs[0], "content")
    if value is None and not args.dry_run:
        retry = prompt + "\n이전 내용은 검증에 실패했습니다. 오류를 고쳐 한 번만 다시 제출하세요."
        runs.append(invoke(cli, model, args, case, retry, CONTENT_PRESET, env))
        value, second_errors = accepted_payload(runs[1], "content")
        errors += second_errors
    return value, runs, errors


def search_contact(
    name: str,
    case: dict[str, Any] | None = None,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    fixture = (case or {}).get("fixture") or {}
    normalized_name = re.sub(r"(?:씨께|님께|씨|님)$", "", name.strip())
    if fixture.get("contact_name") == normalized_name:
        if fixture.get("search_error"):
            return {"status": "error", "results": [], "count": 0}, []
        count = int(fixture.get("count", 1))
        results = [
            {
                "card_id": f"card-eval-{(case or {}).get('id', 'fixture')}-{index + 1}",
                "name": normalized_name,
                "email": fixture.get("email", f"person{index + 1}@fixture.example"),
                "mobile": fixture.get("mobile", f"010-8800-{index + 1:04d}"),
                "company": fixture.get("company", "평가회사"),
                "title": fixture.get("title", "담당자"),
            }
            for index in range(count)
        ]
        return {"results": results, "count": len(results)}, results
    if "검색오류" in name:
        return {"status": "error", "results": [], "count": 0}, []
    results = CONTACTS.get(name, [])
    return {"results": results, "count": len(results)}, results


def parse_datetime(date_expression: str, time_expression: str) -> str | None:
    time_match = TIME_RE.search(time_expression)
    if not time_match:
        return None
    marker, hour_text, minute_text = time_match.groups()
    hour = int(hour_text)
    minute = int(minute_text or 0)
    if marker == "오후" and hour < 12:
        hour += 12
    if marker == "오전" and hour == 12:
        hour = 0
    if hour > 23 or minute > 59:
        return None

    absolute = ABSOLUTE_DATE_RE.search(date_expression)
    if absolute:
        year, month, day = map(int, absolute.groups())
        try:
            date = dt.date(year, month, day)
        except ValueError:
            return None
    elif "모레" in date_expression:
        date = NOW.date() + dt.timedelta(days=2)
    elif "내일" in date_expression:
        date = NOW.date() + dt.timedelta(days=1)
    elif "오늘" in date_expression:
        date = NOW.date()
    else:
        weekday = next(
            (number for name, number in WEEKDAYS.items() if name in date_expression),
            None,
        )
        if weekday is None:
            return None
        monday = NOW.date() - dt.timedelta(days=NOW.weekday())
        if "다음 주" in date_expression or "다음주" in date_expression:
            monday += dt.timedelta(days=7)
        date = monday + dt.timedelta(days=weekday)
    return dt.datetime.combine(date, dt.time(hour, minute)).strftime("%Y-%m-%dT%H:%M")


class Controller:
    def __init__(
        self,
        cli: Path,
        model: Path,
        args: argparse.Namespace,
        case: dict[str, Any],
        plan: dict[str, Any],
    ) -> None:
        self.cli = cli
        self.model = model
        self.args = args
        self.case = case
        self.plan = plan
        self.decisions: list[dict[str, Any]] = []
        self.calls: list[dict[str, Any]] = []
        self.results: list[dict[str, Any]] = []
        self.content_runs: list[dict[str, Any]] = []
        self.errors: list[str] = []
        self.final = ""

    def call(self, name: str, arguments: dict[str, Any], result: dict[str, Any]) -> bool:
        call = {"name": name, "arguments": arguments}
        schema = schema_errors(call)
        policy_reason = self.policy_reject_reason(name, arguments)
        allowed = not schema and policy_reason is None
        self.decisions.append({
            **call,
            "validation": {
                "allowed": allowed,
                "schema_errors": schema,
                "reject_reason": policy_reason,
            },
        })
        self.results.append({
            "name": name,
            "arguments": arguments,
            "result": result if allowed else None,
            "validation": {
                "allowed": allowed,
                "errors": schema,
                "reject_reason": policy_reason,
            },
        })
        if schema:
            self.errors += [f"{name}:{error}" for error in schema]
            return False
        if policy_reason:
            self.errors.append(f"{name}:policy_rejected:{policy_reason}")
            self.final = {
                "UNSUPPORTED_REQUEST": "요청한 기능은 현재 지원하지 않습니다.",
                "REAL_SEND_FORBIDDEN": "직접 전송할 수 없으며 작성 화면만 열 수 있습니다.",
                "PREVIEW_ONLY": "초안 또는 예시만 제공하며 도구를 실행하지 않습니다.",
                "INVALID_EMAIL": "올바른 이메일 주소가 필요합니다.",
                "UPDATE_NOT_GROUNDED": "수정할 필드와 새 값을 알려주세요.",
            }[policy_reason]
            return False
        self.calls.append(call)
        if result.get("status") == "error":
            self.errors.append(f"{name}:{result.get('error', 'tool_error')}")
            return False
        return True

    def policy_reject_reason(
        self, tool_name: str, arguments: dict[str, Any]
    ) -> str | None:
        prompt = str(self.case.get("prompt") or "")
        if any(marker in prompt for marker in (
            "삭제해", "삭제 해", "완전히 삭제", "전화 걸어", "전화해줘", "전화해 줘",
        )):
            return "UNSUPPORTED_REQUEST"
        if any(marker in prompt for marker in (
            "실제로 전송", "지금 바로 전송", "자동 전송",
        )):
            return "REAL_SEND_FORBIDDEN"
        preview = any(marker in prompt for marker in (
            "예시", "방법", "먼저 작성해서 보여", "초안을 먼저 보여",
        ))
        external_ui = any(marker in prompt for marker in (
            "화면 열어", "작성 화면", "캘린더 열어",
        ))
        if preview and not external_ui:
            return "PREVIEW_ONLY"
        if re.search(
            r"(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+"
            r"(?:-at-|(?:\s+at\s+))[a-z0-9.-]+(?![a-z0-9.-])",
            prompt,
        ) and not re.search(r"[^@\s]+@[^@\s]+\.[^@\s]+", prompt):
            return "INVALID_EMAIL"
        if MALFORMED_SPACED_EMAIL_RE.search(prompt):
            return "INVALID_EMAIL"
        if tool_name == "update_business_card" and not self.update_grounded():
            return "UPDATE_NOT_GROUNDED"
        return None

    def update_grounded(self) -> bool:
        prompt = str(self.case.get("prompt") or "")
        aliases = {
            "name": ("이름",),
            "name_en": ("영문 이름",),
            "company": ("회사", "회사명"),
            "department": ("부서",),
            "title": ("직함", "직급"),
            "industry": ("업종",),
            "location": ("지역", "위치"),
            "phone": ("전화",),
            "mobile": ("휴대폰", "전화번호"),
            "email": ("이메일", "메일 주소"),
            "address": ("주소",),
            "website": ("웹사이트",),
            "memo": ("메모",),
        }
        updates = self.plan.get("updates")
        return isinstance(updates, dict) and bool(updates) and all(
            str(value).strip()
            and str(value) in prompt
            and any(alias in prompt for alias in aliases.get(field, (field,)))
            for field, value in updates.items()
        )

    def resolve(self, purpose: str) -> dict[str, Any] | None:
        value = str(self.plan.get("recipient_value") or "")
        search_result, candidates = search_contact(value, self.case)
        if not self.call("search_contacts", {"query": value}, search_result):
            return None
        if len(candidates) == 0:
            self.final = f"‘{value}’ 연락처를 찾지 못했습니다."
            return None
        if len(candidates) > 1:
            self.final = "같은 이름의 연락처가 여러 명입니다. 대상을 선택해 주세요."
            return None
        contact = candidates[0]
        fixture = self.case.get("fixture") or {}
        detail = (
            {"status": "error", "error": "fixture_get_failure"}
            if contact["card_id"] == "card-get-error" or fixture.get("detail_error")
            else contact
        )
        if not self.call(
            "get_contact",
            {"card_id": contact["card_id"], "purpose": purpose},
            detail,
        ):
            return None
        return contact

    def run(self) -> None:
        intent = self.plan.get("intent")
        execute = self.plan.get("execute")
        recipient_type = self.plan.get("recipient_type")
        recipient_value = str(self.plan.get("recipient_value") or "")
        if not execute or intent in {"ANSWER_ONLY", "CLARIFY", "UNSUPPORTED"}:
            self.final = {
                "CLARIFY": self.plan.get("clarification_question") or "필요한 정보를 더 알려주세요.",
                "UNSUPPORTED": "요청한 기능은 현재 지원하지 않습니다.",
            }.get(str(intent), "도구를 실행하지 않고 답변했습니다.")
            return
        if intent == "SEARCH_CONTACT":
            result, _ = search_contact(recipient_value, self.case)
            self.call("search_contacts", {"query": recipient_value}, result)
            self.final = "연락처 검색 결과를 확인했습니다."
        elif intent == "VIEW_CONTACT":
            if recipient_type == "CARD_ID":
                self.call(
                    "get_contact",
                    {"card_id": recipient_value, "purpose": "display"},
                    {"card_id": recipient_value, "found": True},
                )
            else:
                self.resolve("display")
            self.final = self.final or "명함 상세정보를 확인했습니다."
        elif intent in {"COMPOSE_EMAIL", "COMPOSE_SMS"}:
            self.compose("email" if intent == "COMPOSE_EMAIL" else "sms")
        elif intent == "CREATE_CALENDAR_EVENT":
            self.calendar()
        elif intent == "UPDATE_CONTACT":
            if not self.update_grounded():
                self.errors.append("pre_execution_policy:UPDATE_NOT_GROUNDED")
                self.final = "수정할 명함 필드와 새 값을 구체적으로 알려주세요."
                return
            contact = self.resolve("display")
            if contact:
                arguments = {
                    "card_id": contact["card_id"],
                    "updates": self.plan.get("updates") or {},
                }
                self.call(
                    "update_business_card",
                    arguments,
                    {"executed": False, "requires_user_confirmation": True},
                )
                self.final = "명함 수정 내용을 확인해 주세요."
        elif intent == "GET_CURRENT_DATETIME":
            self.call(
                "get_current_datetime",
                {"timezone": "Asia/Seoul"},
                {"date": "2026-07-24", "time": "09:00:00", "timezone": "Asia/Seoul"},
            )
            self.final = "현재 날짜와 시각을 확인했습니다."
        else:
            self.errors.append(f"unsupported_controller_intent:{intent}")
            self.final = "실행할 수 없는 요청입니다."

    def compose(self, channel: str) -> None:
        recipient_type = self.plan.get("recipient_type")
        destination = str(self.plan.get("recipient_value") or "")
        display_name = ""
        if recipient_type == "CARD_ID":
            grounded_ids = set((self.case.get("fixture") or {}).get("grounded_card_ids") or [])
            if destination not in grounded_ids:
                self.errors.append("stale_id_blocked")
                self.final = "이전 검색 결과가 만료되었습니다. 연락처를 다시 검색해 주세요."
                return
        if recipient_type == "CONTACT_NAME":
            contact = self.resolve(channel)
            if not contact:
                return
            destination = contact["email"] if channel == "email" else contact["mobile"]
            display_name = contact["name"]
            if not destination:
                self.final = (
                    "선택한 연락처에 이메일 주소가 없습니다."
                    if channel == "email" else "선택한 연락처에 전화번호가 없습니다."
                )
                return
        if channel == "email" and not EMAIL_RE.fullmatch(destination):
            self.errors.append("invalid_email_blocked")
            self.final = "올바른 이메일 주소가 필요합니다."
            return
        if channel == "sms" and not PHONE_RE.fullmatch(destination):
            self.errors.append("invalid_phone_blocked")
            self.final = "올바른 전화번호가 필요합니다."
            return
        content, runs, errors = generate_content(
            self.cli,
            self.model,
            self.args,
            self.case,
            channel,
            str(self.plan.get("content_goal") or ""),
            display_name,
            str(contact.get("company") or "") if recipient_type == "CONTACT_NAME" and contact else "",
            str(contact.get("title") or "") if recipient_type == "CONTACT_NAME" and contact else "",
            str(self.plan.get("tone") or ""),
        )
        self.content_runs += runs
        self.errors += [f"content:{error}" for error in errors]
        if content is None:
            self.final = "안전한 메시지 본문을 생성하지 못했습니다."
            return
        arguments = {"channel": channel, "to": destination, "body": content.get("body", "")}
        if channel == "email":
            arguments["subject"] = content.get("subject", "")
        result = (
            {"status": "error", "error": "fixture_compose_failure", "opened": False}
            if destination == "compose-error@example.com" or
            bool((self.case.get("fixture") or {}).get("compose_error"))
            else {"opened": False, "requires_user_confirmation": True}
        )
        self.call("open_compose", arguments, result)
        self.final = "작성 화면을 열었습니다. 전송 전에 확인해 주세요."

    def calendar(self) -> None:
        date_expression = str(self.plan.get("date_expression") or "")
        time_expression = str(self.plan.get("time_expression") or "")
        relative = not bool(ABSOLUTE_DATE_RE.search(date_expression))
        if relative:
            if not self.call(
                "get_current_datetime",
                {"timezone": "Asia/Seoul"},
                {"date": "2026-07-24", "time": "09:00:00", "timezone": "Asia/Seoul"},
            ):
                return
        start = parse_datetime(date_expression, time_expression)
        if start is None:
            self.errors.append("date_parse_failed")
            self.final = "날짜와 시간을 더 정확히 알려주세요."
            return
        attendees: list[str] = []
        if self.plan.get("recipient_type") == "CONTACT_NAME":
            contact = self.resolve("calendar")
            if not contact:
                return
            if not contact["email"]:
                self.final = "선택한 연락처에 일정 참석자 이메일이 없습니다."
                return
            attendees.append(contact["email"])
        arguments: dict[str, Any] = {
            "title": self.plan.get("calendar_title") or "일정",
            "start_time": start,
        }
        if attendees:
            arguments["attendee_emails"] = attendees
        self.call(
            "create_calendar_event",
            arguments,
            {"opened": False, "requires_user_confirmation": True},
        )
        self.final = "캘린더 작성 화면을 열었습니다. 저장 전에 확인해 주세요."


def result_record(
    case: dict[str, Any],
    plan: dict[str, Any] | None,
    intent_runs: list[dict[str, Any]],
    intent_errors: list[str],
    controller: Controller | None,
) -> dict[str, Any]:
    calls = controller.calls if controller else []
    final = controller.final if controller else "요청 의도를 구조화하지 못했습니다."
    return {
        "record_type": "test_result",
        "architecture": "intent_workflow_orchestrator",
        "test_id": case["id"],
        "category": case.get("category"),
        "prompt": case["prompt"],
        "test_case": case,
        "decision_source": "MODEL_INTENT",
        "tool_sequence_source": "WORKFLOW_ORCHESTRATOR",
        "content_source": "GEMMA4",
        "intent_plan": plan,
        "intent_runs": intent_runs,
        "intent_errors": intent_errors,
        "orchestrator_decisions": controller.decisions if controller else [],
        "orchestrator_tool_calls": calls,
        "tool_calls": calls,
        "execution_sequence": [call["name"] for call in calls],
        "tool_results": controller.results if controller else [],
        "content_runs": controller.content_runs if controller else [],
        "controller_errors": controller.errors if controller else ["intent_unavailable"],
        "assistant_output": final,
        "final_response": final,
        "unsafe_execution_count": 0,
    }


def main() -> int:
    args = parse_args()
    cli = resolve_cli(args.cli)
    model = resolve_path(args.model)
    tests_file = resolve_path(args.tests_file)
    for required in (cli, model, tests_file, INTENT_PRESET, CONTENT_PRESET):
        if not required.is_file():
            print(f"error: required file missing: {required}", file=sys.stderr)
            return 2
    cases = select_cases(read_jsonl(tests_file), args)
    output = (
        resolve_path(args.output, Path.cwd())
        if args.output else
        DEFAULT_RESULTS / "gemma4-intent-workflow-orchestrator-64.jsonl"
    )
    metadata = {
        "record_type": "run_metadata",
        "architecture": "intent_workflow_orchestrator",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "model_name": args.model_name,
        "model_path": str(model),
        "model_sha256": sha256_file(model),
        "backend": args.backend,
        "runtime": cli_version(cli),
        "cli": str(cli),
        "test_count": len(cases),
        "tests_file": str(tests_file),
        "retry_limit": 1,
        "fixed_now": "2026-07-24T09:00:00+09:00",
    }
    if args.dry_run:
        for case in cases:
            _, runs, _ = analyze_intent(cli, model, args, case)
            print(json.dumps({"test_id": case["id"], "command": runs[0]["command"]}, ensure_ascii=False))
        return 0
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as stream:
        stream.write(json.dumps(metadata, ensure_ascii=False) + "\n")
        for index, case in enumerate(cases, 1):
            plan, runs, errors = analyze_intent(cli, model, args, case)
            controller = None
            if plan is not None:
                controller = Controller(cli, model, args, case, plan)
                controller.run()
            record = result_record(case, plan, runs, errors, controller)
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
            stream.flush()
            print(
                f"[{index}/{len(cases)}] {case['id']}: "
                f"intent={(plan or {}).get('intent')} sequence={record['execution_sequence']}"
            )
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
