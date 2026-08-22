"""Small constrained schemas for staged Gemma intent extraction."""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any

import litert_lm


INTENTS = [
    "SEARCH_CONTACT",
    "VIEW_CONTACT",
    "COMPOSE_EMAIL",
    "COMPOSE_SMS",
    "CREATE_CALENDAR_EVENT",
    "UPDATE_CONTACT",
    "GET_CURRENT_DATETIME",
    "GENERAL",
]
ACTIONS = ["EXECUTE", "PREVIEW", "ANSWER", "CLARIFY", "UNSUPPORTED"]
RECIPIENT_TYPES = ["CONTACT_NAME", "EMAIL", "PHONE", "CARD_ID"]
UPDATE_FIELDS = [
    "name", "name_en", "company", "department", "title", "industry",
    "location", "phone", "mobile", "email", "address", "website", "memo",
]
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
PHONE_RE = re.compile(r"^(?:\+82[- ]?|0)\d{1,2}[- ]?\d{3,4}[- ]?\d{4}$")
AGENT_INSTRUCTION_RE = re.compile(
    r"작성\s*화면|화면\s*열|전송(?:이)?\s*완료|전송했다고\s*말|"
    r"저장(?:이)?\s*완료|저장\s*완료됐다고\s*말"
)


def string(enum: list[str] | None = None, description: str = "") -> dict[str, Any]:
    value: dict[str, Any] = {"type": "string"}
    if enum:
        value["enum"] = enum
    if description:
        value["description"] = description
    return value


def schema(required: list[str], properties: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": "object",
        "additionalProperties": False,
        "required": required,
        "properties": properties,
    }


STAGE1_SCHEMA = schema(
    ["intent", "action"],
    {
        "intent": string(INTENTS, "요청의 의미 유형"),
        "action": string(ACTIONS, "실행, 미리보기, 답변, 추가질문, 미지원"),
    },
)

SLOT_SCHEMAS: dict[str, tuple[str, dict[str, Any]]] = {
    "SEARCH_CONTACT": (
        "submit_search_slots",
        schema(["contact_name"], {"contact_name": string(description="검색할 이름")}),
    ),
    "VIEW_CONTACT": (
        "submit_view_slots",
        schema(
            ["recipient_type", "recipient_value"],
            {
                "recipient_type": string(["CONTACT_NAME", "CARD_ID"]),
                "recipient_value": string(description="원문 이름 또는 card ID"),
            },
        ),
    ),
    "COMPOSE_EMAIL": (
        "submit_email_slots",
        schema(
            ["recipient_type", "recipient_value", "content_goal"],
            {
                "recipient_type": string(
                    ["CONTACT_NAME", "EMAIL", "CARD_ID"],
                    "사람 이름은 CONTACT_NAME(없는사람·오류사람 포함), @와 도메인이 유효한 주소만 EMAIL",
                ),
                "recipient_value": string(description="원문 이름 또는 email"),
                "content_goal": string(description="사용자가 제공한 사실과 전달 목적"),
                "tone": string(description="요청한 말투. 없으면 자연스럽고 정중하게"),
            },
        ),
    ),
    "COMPOSE_SMS": (
        "submit_sms_slots",
        schema(
            ["recipient_type", "recipient_value", "content_goal"],
            {
                "recipient_type": string(
                    ["CONTACT_NAME", "PHONE", "CARD_ID"],
                    "사람 이름은 CONTACT_NAME(없는사람·오류사람 포함), 유효한 숫자 전화번호만 PHONE",
                ),
                "recipient_value": string(description="원문 이름 또는 전화번호"),
                "content_goal": string(description="사용자가 제공한 사실과 전달 목적"),
                "tone": string(description="요청한 말투. 없으면 자연스럽고 정중하게"),
            },
        ),
    ),
    "CREATE_CALENDAR_EVENT": (
        "submit_calendar_slots",
        schema(
            ["title", "date_expression", "time_expression", "attendee_type", "contact_name"],
            {
                "title": string(description="일정 제목"),
                "date_expression": string(description="계산하지 않은 날짜 원문"),
                "time_expression": string(description="계산하지 않은 시간 원문"),
                "attendee_type": string(
                    ["NONE", "CONTACT_NAME"],
                    "사람 이름이 원문에 있으면 CONTACT_NAME, 없으면 NONE",
                ),
                "contact_name": string(
                    description="원문 참석자 이름. attendee_type=NONE이면 빈 문자열"
                ),
            },
        ),
    ),
    "UPDATE_CONTACT": (
        "submit_update_slots",
        schema(
            ["contact_name", "update_field", "update_value"],
            {
                "contact_name": string(description="수정할 연락처 이름"),
                "update_field": string(UPDATE_FIELDS),
                "update_value": string(description="사용자가 직접 제공한 새 값"),
            },
        ),
    ),
}


class SchemaTool(litert_lm.Tool):
    def __init__(self, name: str, description: str, parameters: dict[str, Any]) -> None:
        self.name = name
        self.description = description
        self.parameters = parameters

    def __str__(self) -> str:
        return self.name

    def get_tool_description(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters,
            },
        }

    def execute(self, param: Mapping[str, Any]) -> dict[str, Any]:
        return {"accepted": True, "value": dict(param)}


def stage1_tool() -> SchemaTool:
    return SchemaTool(
        "submit_action",
        "사용자 요청의 의미 유형과 action만 제출합니다.",
        STAGE1_SCHEMA,
    )


def slot_tool(intent: str) -> SchemaTool | None:
    definition = SLOT_SCHEMAS.get(intent)
    if not definition:
        return None
    name, parameters = definition
    return SchemaTool(name, f"{intent}에 필요한 slot만 제출합니다.", parameters)


def validate_stage1(value: Any) -> list[str]:
    if not isinstance(value, dict):
        return ["output must be an object"]
    errors = []
    if value.get("intent") not in INTENTS:
        errors.append("intent: allowed=" + ",".join(INTENTS))
    if value.get("action") not in ACTIONS:
        errors.append("action: allowed=" + ",".join(ACTIONS))
    if value.get("action") == "EXECUTE" and value.get("intent") == "GENERAL":
        errors.append("GENERAL cannot use EXECUTE")
    if value.get("action") in {"ANSWER", "PREVIEW"} and value.get("intent") != "GENERAL":
        errors.append("ANSWER/PREVIEW requires GENERAL")
    return errors


def validate_slots(intent: str, value: Any) -> list[str]:
    if not isinstance(value, dict):
        return ["slot output must be an object"]
    definition = SLOT_SCHEMAS.get(intent)
    if not definition:
        return []
    _, spec = definition
    errors = []
    for field in spec["required"]:
        allow_empty = intent == "CREATE_CALENDAR_EVENT" and field == "contact_name"
        if not isinstance(value.get(field), str) or (
            not allow_empty and not value[field].strip()
        ):
            errors.append(f"{field}: required non-empty string")
    unknown = set(value) - set(spec["properties"])
    if unknown:
        errors.append("unknown fields: " + ",".join(sorted(unknown)))
    recipient_type = value.get("recipient_type")
    recipient_value = value.get("recipient_value")
    if recipient_type == "EMAIL" and (
        not isinstance(recipient_value, str) or not EMAIL_RE.fullmatch(recipient_value)
    ):
        errors.append("recipient_value: valid email required")
    if recipient_type == "PHONE" and (
        not isinstance(recipient_value, str) or not PHONE_RE.fullmatch(recipient_value)
    ):
        errors.append("recipient_value: valid phone required")
    if intent == "UPDATE_CONTACT" and value.get("update_field") not in UPDATE_FIELDS:
        errors.append("update_field: invalid enum")
    # A mixed user request may legitimately contain both recipient-facing text
    # and an unsafe UI/completion instruction. Do not discard otherwise valid
    # recipient slots here. The separate content generator receives the raw
    # request, removes agent instructions, and its validator blocks completion
    # claims before open_compose.
    if intent == "CREATE_CALENDAR_EVENT":
        attendee_type = value.get("attendee_type")
        name = value.get("contact_name")
        if attendee_type not in {"NONE", "CONTACT_NAME"}:
            errors.append("attendee_type: invalid enum")
        if attendee_type == "CONTACT_NAME" and (
            not isinstance(name, str) or not name.strip()
        ):
            errors.append("contact_name: required for CONTACT_NAME")
        if attendee_type == "NONE" and isinstance(name, str) and name.strip():
            errors.append("contact_name: must be empty for NONE")
    return errors
