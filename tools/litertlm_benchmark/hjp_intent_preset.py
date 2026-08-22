"""Constrained high-level intent output for the orchestrated agent benchmark."""

from __future__ import annotations

import copy
import os
import re
from collections.abc import Mapping
from typing import Any

import litert_lm


INTENTS = [
    "ANSWER_ONLY",
    "CLARIFY",
    "SEARCH_CONTACT",
    "VIEW_CONTACT",
    "COMPOSE_EMAIL",
    "COMPOSE_SMS",
    "CREATE_CALENDAR_EVENT",
    "UPDATE_CONTACT",
    "GET_CURRENT_DATETIME",
    "UNSUPPORTED",
]

RECIPIENT_TYPES = [
    "NONE",
    "CONTACT_NAME",
    "EMAIL",
    "PHONE",
    "CARD_ID",
]

UPDATABLE_FIELDS = [
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
]

EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
PHONE_RE = re.compile(r"^\+?[0-9][0-9 ()-]{6,19}$")


def _string(description: str, enum: list[str] | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {"type": "string", "description": description}
    if enum is not None:
        result["enum"] = enum
    return result


INTENT_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": [
        "intent",
        "execute",
        "recipient_type",
        "recipient_value",
    ],
    "properties": {
        "intent": _string("사용자의 최상위 의도", INTENTS),
        "execute": {
            "type": "boolean",
            "description": "실제 검색, 조회, 수정 또는 외부 작성 화면 실행을 명확히 요청했으면 true",
        },
        "recipient_type": _string("대상 식별 방식", RECIPIENT_TYPES),
        "recipient_value": _string(
            "사용자 원문에서 추출한 이름, 주소, 번호 또는 card ID. 없으면 빈 문자열"
        ),
        "content_goal": _string("사용자가 제공한 메시지 목적과 사실만 요약. 없으면 빈 문자열"),
        "date_expression": _string("계산하지 않은 한국어 날짜 원문. 없으면 빈 문자열"),
        "time_expression": _string("계산하지 않은 한국어 시간 원문. 없으면 빈 문자열"),
        "calendar_title": _string("사용자가 요청한 일정 제목. 없으면 빈 문자열"),
        "update_field": _string("UPDATE_CONTACT일 때만 변경할 단일 필드", UPDATABLE_FIELDS),
        "update_value": _string("UPDATE_CONTACT일 때만 사용자가 제공한 새 값"),
        "clarification_question": _string("CLARIFY일 때 필요한 짧은 한국어 질문. 아니면 빈 문자열"),
    },
}


def _submit_intent(arguments: Mapping[str, Any]) -> dict[str, Any]:
    normalized = copy.deepcopy(dict(arguments))
    if normalized.get("intent") in {"ANSWER_ONLY", "CLARIFY", "UNSUPPORTED"}:
        normalized.setdefault("recipient_type", "NONE")
        normalized.setdefault("recipient_value", "")
    errors = validate_intent_arguments(normalized)
    if errors:
        return {
            "dry_run": True,
            "status": "rejected",
            "reason": "INTENT_SCHEMA_INVALID",
            "errors": errors,
            "message": (
                "누락되거나 모순된 필드를 고쳐 submit_intent를 한 번만 다시 호출하세요. "
                "필수 정보가 없으면 CLARIFY/false, 삭제·전화·실제 전송은 UNSUPPORTED/false입니다."
            ),
        }
    update_field = normalized.pop("update_field", None)
    update_value = normalized.pop("update_value", None)
    if update_field:
        normalized["updates"] = {str(update_field): str(update_value or "")}
    return {
        "dry_run": True,
        "accepted": True,
        "structured_intent": normalized,
        "message": "의도만 기록했습니다. Android tool은 실행하지 않았습니다.",
    }


def validate_intent_arguments(arguments: Mapping[str, Any]) -> list[str]:
    errors: list[str] = []
    required = [
        "intent",
        "execute",
        "recipient_type",
        "recipient_value",
    ]
    for field in required:
        if field not in arguments:
            errors.append(f"missing:{field}")
    intent = arguments.get("intent")
    execute = arguments.get("execute")
    recipient_type = arguments.get("recipient_type")
    recipient_value = arguments.get("recipient_value")
    if intent not in INTENTS:
        errors.append("invalid:intent")
    if not isinstance(execute, bool):
        errors.append("invalid:execute")
    if recipient_type not in RECIPIENT_TYPES:
        errors.append("invalid:recipient_type")
    if not isinstance(recipient_value, str):
        errors.append("invalid:recipient_value")
    elif recipient_type == "EMAIL" and not EMAIL_RE.fullmatch(recipient_value):
        errors.append("invalid:recipient_value_not_email")
    elif recipient_type == "PHONE" and not PHONE_RE.fullmatch(recipient_value):
        errors.append("invalid:recipient_value_not_phone")
    elif recipient_type == "NONE" and recipient_value:
        errors.append("contradiction:none_recipient_must_be_empty")
    if intent in {"ANSWER_ONLY", "CLARIFY", "UNSUPPORTED"} and execute is not False:
        errors.append("contradiction:execute_must_be_false")
    if intent not in {"ANSWER_ONLY", "CLARIFY", "UNSUPPORTED"} and execute is not True:
        errors.append("contradiction:execute_must_be_true")
    if intent in {"COMPOSE_EMAIL", "COMPOSE_SMS"}:
        if recipient_type == "NONE" or not recipient_value:
            errors.append("contradiction:compose_recipient_required")
    if intent == "CREATE_CALENDAR_EVENT":
        if not arguments.get("date_expression"):
            errors.append("contradiction:calendar_date_required")
        if not arguments.get("time_expression"):
            errors.append("contradiction:calendar_time_required")
    update_field = arguments.get("update_field")
    update_value = arguments.get("update_value")
    if intent == "UPDATE_CONTACT" and (
        update_field not in UPDATABLE_FIELDS
        or not isinstance(update_value, str)
        or not update_value.strip()
    ):
        errors.append("contradiction:update_fields_required")
    if intent != "UPDATE_CONTACT" and (update_field or update_value):
        errors.append("contradiction:update_fields_only_for_update_contact")
    if intent == "CLARIFY" and not arguments.get("clarification_question"):
        errors.append("contradiction:clarification_question_required")
    original = os.environ.get("HJP_BENCHMARK_PROMPT", "")
    if any(marker in original for marker in (
        "삭제해", "삭제 해", "완전히 삭제", "전화 걸어", "전화해줘",
        "실제로 전송", "지금 바로 전송", "자동 전송",
    )) and intent != "UNSUPPORTED":
        errors.append("contradiction:unsupported_request_must_be_unsupported")
    return errors


class IntentTool(litert_lm.Tool):
    def __str__(self) -> str:
        return "submit_intent"

    def get_tool_description(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": "submit_intent",
                "description": (
                    "사용자 요청을 저수준 도구가 아닌 하나의 구조화된 작업 계획으로 제출합니다."
                ),
                "parameters": INTENT_SCHEMA,
            },
        }

    def execute(self, arguments: Mapping[str, Any]) -> dict[str, Any]:
        return _submit_intent(arguments)


tools = [IntentTool()]

system_instruction = (
    "You are a model that classifies one Korean request. "
    "Call submit_intent once and never print a low-level tool name. If it returns status=rejected, "
    "fix every listed field and call submit_intent only one more time. "
    "Use ANSWER_ONLY with execute=false for explanations, examples, advice, greetings, "
    "or a draft the user only wants to see. Use CLARIFY with execute=false when a required "
    "recipient, update value, date, or time is missing or malformed. Use UNSUPPORTED with "
    "execute=false for deletion, real sending, phone calls, weather, or unsupported actions. "
    "For an explicitly requested supported search, view, compose screen, calendar draft, "
    "contact update, or current time lookup, use the matching intent and execute=true. "
    "Distinguish CONTACT_NAME, EMAIL, PHONE, CARD_ID, and NONE. A Korean personal name such as "
    "김지원 is CONTACT_NAME, never EMAIL. EMAIL must contain a valid @ address and PHONE must be "
    "a real number written by the user. Never invent a recipient. "
    "Always fill recipient_type and recipient_value; use NONE and an empty value when absent. "
    "Put message purpose and facts only in content_goal. Never use update_field or update_value "
    "unless intent is UPDATE_CONTACT. "
    "Extract date_expression and time_expression verbatim; never calculate a date. "
    "Preserve only user-provided facts in content_goal."
)
