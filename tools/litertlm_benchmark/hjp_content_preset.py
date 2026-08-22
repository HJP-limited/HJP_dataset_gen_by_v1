"""Constrained email/SMS content generation for the orchestrated benchmark."""

from __future__ import annotations

import os
import re
from collections.abc import Mapping
from typing import Any

import litert_lm


PLACEHOLDER_RE = re.compile(r"\[[^\]]+\]|client님|ooo님|당신의 이름", re.IGNORECASE)
TIME_FACT_RE = re.compile(
    r"오늘|내일|모레|다음\s*주|이번\s*주|[월화수목금토일]요일|"
    r"[오전후]+\s*\d{1,2}시|\d{4}년\s*\d{1,2}월\s*\d{1,2}일"
)
FALSE_COMPLETION_RE = re.compile(
    r"(메일|이메일|문자).{0,12}(전송(?:이)?\s*완료|보냈습니다|발송(?:이)?\s*완료)"
    r"|일정.{0,12}(저장|등록)\s*완료"
)
INSTRUCTION_ECHO_RE = re.compile(
    r"작성\s*화면\s*열|전송했다고\s*말|저장\s*완료됐다고\s*말"
)
CHANNEL = os.environ.get("HJP_CONTENT_CHANNEL", "email").lower()
ORIGINAL = os.environ.get("HJP_BENCHMARK_PROMPT", "")
GOAL = os.environ.get("HJP_CONTENT_GOAL", "")
RECIPIENT_NAME = os.environ.get("HJP_RECIPIENT_NAME", "")
RECIPIENT_COMPANY = os.environ.get("HJP_RECIPIENT_COMPANY", "")
RECIPIENT_TITLE = os.environ.get("HJP_RECIPIENT_TITLE", "")
REQUESTED_TONE = os.environ.get("HJP_REQUESTED_TONE", "")
USER_FACTS = os.environ.get("HJP_USER_FACTS", ORIGINAL)


def _schema() -> dict[str, Any]:
    properties = {
        "body": {
            "type": "string",
            "description": "placeholder 없이 사용자가 제공한 사실만 포함한 자연스러운 한국어 본문",
        }
    }
    required = ["body"]
    if CHANNEL == "email":
        properties["subject"] = {
            "type": "string",
            "description": "간결하고 자연스러운 한국어 이메일 제목",
        }
        required.insert(0, "subject")
    return {
        "type": "object",
        "additionalProperties": False,
        "required": required,
        "properties": properties,
    }


def _validate(arguments: Mapping[str, Any]) -> list[str]:
    errors: list[str] = []
    body = arguments.get("body")
    subject = arguments.get("subject")
    if not isinstance(body, str) or not body.strip():
        errors.append("body_required")
    if CHANNEL == "email" and (
        not isinstance(subject, str) or not subject.strip()
    ):
        errors.append("email_subject_required")
    if CHANNEL == "sms" and subject is not None:
        errors.append("sms_subject_forbidden")
    combined = "\n".join(
        value for value in (subject, body) if isinstance(value, str)
    )
    if PLACEHOLDER_RE.search(combined):
        errors.append("placeholder_forbidden")
    if FALSE_COMPLETION_RE.search(combined):
        errors.append("false_completion_forbidden")
    if INSTRUCTION_ECHO_RE.search(combined):
        errors.append("agent_instruction_echo_forbidden")
    allowed = ORIGINAL + "\n" + GOAL
    for match in TIME_FACT_RE.finditer(combined):
        if match.group(0) not in allowed:
            errors.append(f"invented_time_fact:{match.group(0)}")
            break
    return errors


class ContentTool(litert_lm.Tool):
    def __str__(self) -> str:
        return (
            "submit_email_content"
            if CHANNEL == "email"
            else "submit_sms_content"
        )

    def get_tool_description(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": str(self),
                "description": "검증할 한국어 메시지 내용만 제출합니다.",
                "parameters": _schema(),
            },
        }

    def execute(self, arguments: Mapping[str, Any]) -> dict[str, Any]:
        errors = _validate(arguments)
        if errors:
            return {
                "dry_run": True,
                "status": "rejected",
                "reason": "CONTENT_INVALID",
                "errors": errors,
                "message": "본문 오류를 모두 고쳐 같은 tool을 한 번만 다시 호출하세요.",
            }
        return {
            "dry_run": True,
            "accepted": True,
            "content": dict(arguments),
            "message": "내용만 검증했습니다. 작성 화면을 열거나 전송하지 않았습니다.",
        }


tools = [ContentTool()]

system_instruction = (
    "자연스러운 한국어 메시지 내용만 생성하고 제공된 schema tool 하나만 호출하세요. "
    "사용자 원문, 확정된 수신자 이름·회사·직함 중 제공된 값, content goal과 요청 말투를 반영하세요. "
    "수신자 이름을 알면 자연스러운 호칭으로 시작하되 이름이 없으면 억지 호칭을 만들지 마세요. "
    "사용자가 제공하지 않은 사실·날짜·약속·계약·금액을 만들지 마세요. "
    "실제 전송 완료를 말하거나 [이름], [본인 이름], client님, OOO 같은 placeholder를 쓰지 마세요. "
    "'전송했다고 말해줘', '저장 완료라고 말해줘', '화면을 열어줘'는 에이전트 행동 지시이며 메시지 본문에 넣지 마세요. "
    "그런 지시가 원문에 있어도 수신자에게 전달할 명시적 내용만 본문으로 작성하세요. "
    "이메일은 간결한 제목과 2~5문장의 본문, 문자는 핵심이 분명한 1~3문장의 본문이어야 합니다. "
    "거절되면 지적된 본문 필드만 한 번 수정하세요. "
    f"확정 수신자={RECIPIENT_NAME}; 회사={RECIPIENT_COMPANY}; 직함={RECIPIENT_TITLE}; "
    f"말투={REQUESTED_TONE}; 사용자 사실={USER_FACTS}"
)
