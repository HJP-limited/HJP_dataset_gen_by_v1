"""Ablation D: prompt + app-compatible schema + stateful workflow/validator."""

from __future__ import annotations

import datetime as dt
import os
from pathlib import Path
import re
import sys
from typing import Any, Callable, Mapping

sys.path.insert(0, str(Path(__file__).resolve().parent))

import litert_lm  # noqa: E402

from hjp_tools_schema_preset import (  # noqa: E402
    system_instruction,
    tools as schema_tools,
)


EMAIL_RE = re.compile(
    r"(?i)\b[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@"
    r"[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?"
    r"(?:\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+(?![A-Z0-9.-])"
)
PHONE_RE = re.compile(r"(?<!\d)(?:\+82[- ]?|0)\d{1,2}[- ]?\d{3,4}[- ]?\d{4}(?!\d)")
INVALID_RECIPIENT_RE = re.compile(
    r"(?i)(?:\b[\w.+-]+-at-[\w.-]+\b|\b[\w.+-]+@\s|\btest\s+example\.com\b|010-[A-Z0-9-]*[A-Z][A-Z0-9-]*)"
)
NAME_TARGET_RE = re.compile(r"[가-힣]{2,16}(?:에게|한테|와|과)|[가-힣]{2,16}\s*명함")
ABSOLUTE_DATE_RE = re.compile(r"(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일")
TIME_RE = re.compile(r"(오전|오후)\s*(\d{1,2})시(?:\s*(\d{1,2})분)?")
NEXT_WEEKDAY_RE = re.compile(r"다음\s*주\s*(월|화|수|목|금|토|일)요일")
STRICT_DATETIME_RE = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$")
WEEKDAYS = {"월": 0, "화": 1, "수": 2, "목": 3, "금": 4, "토": 5, "일": 6}


def _normalize_phone(value: str) -> str:
    digits = "".join(character for character in value if character.isdigit())
    return "0" + digits[2:] if digits.startswith("82") else digits


class HybridWorkflow:
    def __init__(self, prompt: str, test_id: str) -> None:
        self.prompt = prompt
        self.test_id = test_id
        self.direct_emails = {item.lower() for item in EMAIL_RE.findall(prompt)}
        self.direct_phones = {_normalize_phone(item) for item in PHONE_RE.findall(prompt)}
        self.contact_target = not self.direct_emails and not self.direct_phones and bool(
            NAME_TARGET_RE.search(prompt)
        )
        self.calendar_intent = "일정" in prompt or "캘린더" in prompt
        self.compose_intent = any(item in prompt for item in ("메일", "이메일", "문자"))
        self.relative_date = self.calendar_intent and any(
            item in prompt for item in ("오늘", "내일", "모레", "다음 주", "이번 주")
        )
        self.preview_only = any(
            item in prompt for item in ("예시", "방법", "먼저 작성해서 보여", "초안을 먼저 보여")
        ) and "화면 열" not in prompt
        self.unsupported = any(
            item in prompt
            for item in (
                "삭제해",
                "삭제 해",
                "완전히 삭제",
                "실제로 전송",
                "지금 바로 전송",
                "자동 전송",
                "전화 걸어",
                "전화해줘",
                "전화해 줘",
            )
        )
        self.invalid_recipient = bool(INVALID_RECIPIENT_RE.search(prompt))
        self.search_results: list[dict[str, Any]] | None = None
        self.selected_contact: dict[str, Any] | None = None
        self.current_date: dt.date | None = None
        self.rejections = 0
        self.executions: list[str] = []
        self.fingerprints: set[str] = set()

    def invoke(
        self,
        model_name: str,
        arguments: Mapping[str, Any],
        actual_name: str,
        actual_arguments: dict[str, Any],
        executor: Callable[[Mapping[str, Any]], dict[str, Any]],
    ) -> dict[str, Any]:
        before = self._state()
        rejection = self._validate(model_name, actual_name, actual_arguments)
        fingerprint = repr((model_name, sorted(actual_arguments.items())))
        if rejection is None and fingerprint in self.fingerprints:
            rejection = (
                "REPEATED_TOOL_CALL",
                "같은 도구 호출을 반복할 수 없습니다.",
                [],
            )
        if rejection is not None:
            self.rejections += 1
            reason, message, allowed = rejection
            return {
                "dry_run": True,
                "status": "rejected",
                "reason": reason,
                "message": message,
                "allowed_next_tools": allowed,
                "retry_exhausted": self.rejections > 1,
                "policy_trace": {
                    "test_id": self.test_id,
                    "workflow_state_before": before,
                    "raw_model_tool_call": {
                        "name": model_name,
                        "arguments": dict(arguments),
                    },
                    "validation_result": "rejected",
                    "reject_reason": reason,
                    "allowed_next_tools": allowed,
                    "tool_executed": False,
                    "actual_tool_name": actual_name,
                    "actual_arguments": actual_arguments,
                    "execution_sequence": list(self.executions),
                },
            }

        self.fingerprints.add(fingerprint)
        self.executions.append(actual_name)
        result = executor(arguments)
        self._record(actual_name, actual_arguments, result)
        result["policy_trace"] = {
            "test_id": self.test_id,
            "workflow_state_before": before,
            "workflow_state_after": self._state(),
            "raw_model_tool_call": {
                "name": model_name,
                "arguments": dict(arguments),
            },
            "validation_result": "approved",
            "reject_reason": None,
            "allowed_next_tools": [],
            "tool_executed": True,
            "actual_tool_name": actual_name,
            "actual_arguments": actual_arguments,
            "execution_sequence": list(self.executions),
        }
        return result

    def _validate(
        self,
        model_name: str,
        actual_name: str,
        arguments: dict[str, Any],
    ) -> tuple[str, str, list[str]] | None:
        if len(self.executions) >= 6:
            return (
                "TOOL_CALL_LIMIT_REACHED",
                "한 요청에서 실행할 수 있는 도구 횟수를 초과했습니다.",
                [],
            )
        if self.unsupported:
            return ("UNSUPPORTED_REQUEST", "현재 지원하지 않는 기능입니다.", [])
        if self.preview_only:
            return ("TOOL_NOT_REQUESTED", "예시나 초안만 요청되어 도구를 실행하지 않습니다.", [])
        if self.invalid_recipient and actual_name in {
            "search_contacts",
            "get_contact",
            "open_compose",
        }:
            return ("INVALID_RECIPIENT", "수신자 주소 또는 번호 형식이 올바르지 않습니다.", [])
        if (
            self.calendar_intent
            and TIME_RE.search(self.prompt) is None
            and actual_name in {"get_current_datetime", "create_calendar_event"}
        ):
            return (
                "MISSING_REQUIRED_INFORMATION",
                "일정 시작 시각을 먼저 알려주세요.",
                [],
            )

        if actual_name == "get_current_datetime":
            if not self.relative_date and not any(
                item in self.prompt for item in ("현재 시간", "지금 시간", "지금 몇 시", "오늘 날짜")
            ):
                allowed = ["create_calendar_event"] if self.calendar_intent else []
                return (
                    "UNNECESSARY_TOOL_CALL",
                    "현재 날짜와 시각 조회가 필요하지 않습니다.",
                    allowed,
                )
            return None

        if actual_name == "search_contacts":
            supported_contact_action = (
                self.compose_intent
                or self.calendar_intent
                or "수정" in self.prompt
                or "바꿔" in self.prompt
                or "변경" in self.prompt
                or "명함" in self.prompt
                or "연락처 찾아" in self.prompt
            )
            if not self.contact_target or not supported_contact_action:
                return ("TOOL_NOT_REQUESTED", "연락처 검색 요청이 아닙니다.", [])
            return None

        if actual_name == "get_contact":
            card_id = str(arguments.get("card_id", "")).strip()
            if card_id.startswith("card-") and card_id in self.prompt:
                return None
            if self.search_results is None:
                return (
                    "CONTACT_LOOKUP_REQUIRED",
                    "먼저 연락처를 검색해야 합니다.",
                    ["search_contacts"],
                )
            if len(self.search_results) == 0:
                return ("CONTACT_NOT_FOUND", "검색 결과가 없습니다.", [])
            if len(self.search_results) > 1:
                return (
                    "CONTACT_SELECTION_REQUIRED",
                    "같은 이름의 연락처가 여러 명입니다. 사용자가 선택해야 합니다.",
                    [],
                )
            if card_id != self.search_results[0].get("card_id"):
                return (
                    "CONTACT_VALUE_NOT_VERIFIED",
                    "검색 결과의 명함 ID만 조회할 수 있습니다.",
                    ["get_contact"],
                )
            return None

        if actual_name == "open_compose":
            channel = arguments.get("channel")
            to = str(arguments.get("to", "")).strip()
            body = str(arguments.get("body", "")).strip()
            subject = str(arguments.get("subject", "")).strip()
            if not self.compose_intent:
                return ("TOOL_NOT_REQUESTED", "메시지 작성 요청이 아닙니다.", [])
            if not body:
                return ("INVALID_ARGUMENTS", "본문은 필수입니다.", [model_name])
            if channel == "email":
                if EMAIL_RE.fullmatch(to) is None:
                    return ("INVALID_EMAIL", "이메일 주소 형식이 올바르지 않습니다.", [])
                if not subject:
                    return ("INVALID_ARGUMENTS", "이메일 제목은 필수입니다.", [model_name])
            elif channel == "sms":
                if PHONE_RE.fullmatch(to) is None:
                    return ("INVALID_PHONE", "전화번호 형식이 올바르지 않습니다.", [])
                if subject:
                    return ("INVALID_ARGUMENTS", "문자에는 제목을 넣을 수 없습니다.", [model_name])
            else:
                return ("INVALID_ARGUMENTS", "channel이 올바르지 않습니다.", [])

            if self.contact_target:
                stage = self._contact_stage()
                if stage is not None:
                    return stage
                assert self.selected_contact is not None
                verified = (
                    {str(self.selected_contact.get("email", "")).lower()}
                    if channel == "email"
                    else {
                        _normalize_phone(str(self.selected_contact.get("mobile", ""))),
                        _normalize_phone(str(self.selected_contact.get("phone", ""))),
                    }
                )
                normalized = to.lower() if channel == "email" else _normalize_phone(to)
                if not normalized or normalized not in verified:
                    return (
                        "CONTACT_VALUE_NOT_VERIFIED",
                        "조회한 연락처의 주소 또는 번호만 사용할 수 있습니다.",
                        [model_name],
                    )
            else:
                direct = (
                    to.lower() in self.direct_emails
                    if channel == "email"
                    else _normalize_phone(to) in self.direct_phones
                )
                if not direct:
                    return (
                        "CONTACT_VALUE_NOT_VERIFIED",
                        "사용자가 제공한 수신자와 일치하지 않습니다.",
                        [],
                    )
            return None

        if actual_name == "create_calendar_event":
            raw = str(arguments.get("start_time", ""))
            if STRICT_DATETIME_RE.fullmatch(raw) is None:
                return (
                    "INVALID_DATETIME",
                    "start_time은 yyyy-MM-dd'T'HH:mm 형식이어야 합니다.",
                    ["create_calendar_event"],
                )
            try:
                start = dt.datetime.strptime(raw, "%Y-%m-%dT%H:%M")
            except ValueError:
                return ("INVALID_DATETIME", "유효하지 않은 날짜와 시각입니다.", [])
            if self.relative_date and self.current_date is None:
                return (
                    "CURRENT_DATETIME_REQUIRED",
                    "상대 날짜 계산 전에 현재 시각 조회가 필요합니다.",
                    ["get_current_datetime"],
                )
            expected_date = self._expected_date()
            if expected_date is not None and start.date() != expected_date:
                return (
                    "INVALID_DATETIME",
                    "계산한 날짜가 사용자 요청과 일치하지 않습니다.",
                    ["create_calendar_event"],
                )
            expected_time = self._expected_time()
            if expected_time is not None and start.time() != expected_time:
                return (
                    "INVALID_DATETIME",
                    "시각이 사용자 요청과 일치하지 않습니다.",
                    ["create_calendar_event"],
                )
            if self.contact_target:
                stage = self._contact_stage()
                if stage is not None:
                    return stage
                assert self.selected_contact is not None
                email = str(self.selected_contact.get("email", "")).strip()
                attendees = arguments.get("attendee_emails")
                if EMAIL_RE.fullmatch(email) is None or not isinstance(attendees, list) or email not in attendees:
                    return (
                        "CONTACT_VALUE_NOT_VERIFIED",
                        "조회한 참석자 이메일이 필요합니다.",
                        ["create_calendar_event"],
                    )
            return None

        if actual_name == "update_business_card":
            if self.contact_target:
                stage = self._contact_stage()
                if stage is not None:
                    return stage
                assert self.selected_contact is not None
                if arguments.get("card_id") != self.selected_contact.get("card_id"):
                    return (
                        "CONTACT_VALUE_NOT_VERIFIED",
                        "조회한 명함만 수정할 수 있습니다.",
                        ["update_business_card"],
                    )
            updates = arguments.get("updates") or {}
            clear_fields = arguments.get("clear_fields") or []
            if not updates and not clear_fields:
                return ("INVALID_ARGUMENTS", "수정할 필드가 필요합니다.", [])
            if set(updates) & set(clear_fields):
                return (
                    "INVALID_ARGUMENTS",
                    "같은 필드를 수정하면서 비울 수 없습니다.",
                    ["update_business_card"],
                )
        return None

    def _contact_stage(self) -> tuple[str, str, list[str]] | None:
        if self.search_results is None:
            return (
                "CONTACT_LOOKUP_REQUIRED",
                "먼저 연락처를 검색해야 합니다.",
                ["search_contacts"],
            )
        if len(self.search_results) == 0:
            return ("CONTACT_NOT_FOUND", "검색 결과가 없습니다.", [])
        if len(self.search_results) > 1:
            return (
                "CONTACT_SELECTION_REQUIRED",
                "같은 이름의 연락처가 여러 명입니다. 사용자가 선택해야 합니다.",
                [],
            )
        if self.selected_contact is None:
            return (
                "CONTACT_DETAIL_REQUIRED",
                "연락처 상세 조회가 필요합니다.",
                ["get_contact"],
            )
        return None

    def _record(
        self,
        actual_name: str,
        arguments: dict[str, Any],
        result: dict[str, Any],
    ) -> None:
        if result.get("status") == "error":
            return
        if actual_name == "search_contacts":
            results = result.get("results")
            self.search_results = list(results) if isinstance(results, list) else []
        elif actual_name == "get_contact" and result.get("found", True):
            self.selected_contact = dict(result)
        elif actual_name == "get_current_datetime":
            try:
                self.current_date = dt.date.fromisoformat(str(result.get("date")))
            except ValueError:
                self.current_date = None

    def _expected_date(self) -> dt.date | None:
        absolute = ABSOLUTE_DATE_RE.search(self.prompt)
        if absolute:
            try:
                return dt.date(*(int(value) for value in absolute.groups()))
            except ValueError:
                return None
        if self.current_date is None:
            return None
        if "내일" in self.prompt:
            return self.current_date + dt.timedelta(days=1)
        if "오늘" in self.prompt:
            return self.current_date
        weekday = NEXT_WEEKDAY_RE.search(self.prompt)
        if weekday:
            days_to_monday = (7 - self.current_date.weekday()) % 7
            if days_to_monday == 0:
                days_to_monday = 7
            return self.current_date + dt.timedelta(
                days=days_to_monday + WEEKDAYS[weekday.group(1)]
            )
        return None

    def _expected_time(self) -> dt.time | None:
        match = TIME_RE.search(self.prompt)
        if match is None:
            return None
        marker, raw_hour, raw_minute = match.groups()
        hour = int(raw_hour)
        minute = int(raw_minute or 0)
        if marker == "오후" and hour < 12:
            hour += 12
        if marker == "오전" and hour == 12:
            hour = 0
        try:
            return dt.time(hour, minute)
        except ValueError:
            return None

    def _state(self) -> dict[str, Any]:
        return {
            "search_count": None if self.search_results is None else len(self.search_results),
            "selected_card_id": None
            if self.selected_contact is None
            else self.selected_contact.get("card_id"),
            "current_date": None if self.current_date is None else self.current_date.isoformat(),
            "rejection_count": self.rejections,
            "execution_sequence": list(self.executions),
        }


class PolicyTool(litert_lm.Tool):
    def __init__(self, wrapped: Any, workflow: HybridWorkflow) -> None:
        self.wrapped = wrapped
        self.workflow = workflow
        self.name = wrapped.name
        self.description = wrapped.description
        self.parameters = wrapped.parameters

    def get_tool_description(self) -> dict[str, Any]:
        return self.wrapped.get_tool_description()

    def execute(self, param: Mapping[str, Any]) -> dict[str, Any]:
        model_arguments = dict(param)
        if self.name == "compose_email":
            actual_name = "open_compose"
            actual_arguments = {"channel": "email", **model_arguments}
        elif self.name == "compose_sms":
            actual_name = "open_compose"
            actual_arguments = {"channel": "sms", **model_arguments}
        else:
            actual_name = self.name
            actual_arguments = model_arguments
        return self.workflow.invoke(
            self.name,
            model_arguments,
            actual_name,
            actual_arguments,
            self.wrapped.executor,
        )

    def __str__(self) -> str:
        return self.name


workflow = HybridWorkflow(
    os.environ.get("HJP_BENCHMARK_PROMPT", ""),
    os.environ.get("HJP_BENCHMARK_TEST_ID", ""),
)
tools = [PolicyTool(tool, workflow) for tool in schema_tools]
