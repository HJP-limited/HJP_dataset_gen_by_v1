"""Small capability and slot-normalization policies used by the Mac harness.

These functions never infer an intent or create a tool call. They only validate
an already structured model decision or remove UI-operation suffixes from a
model-provided calendar title when the shorter title is grounded verbatim.
"""

from __future__ import annotations

import re
from typing import Any, Optional


REAL_SEND = re.compile(
    r"(?:(?:실제로|자동(?:으로)?|지금\s*바로).{0,12}(?:전송|발송|보내)|"
    r"(?:전송|발송|보내).{0,12}(?:실제로|자동(?:으로)?|지금\s*바로))"
)
DELETE = re.compile(r"(?:삭제|지워|제거)(?:해|하|해\s*줘|해\s*주세요)?")
PHONE_CALL = re.compile(r"(?:전화|통화).{0,8}(?:걸어|해\s*줘|연결)")
UPLOAD = re.compile(r"(?:외부|서버|클라우드).{0,10}(?:업로드|전송)")
TITLE_SUFFIXES = (
    " 캘린더 작성 단계까지 준비",
    " 캘린더 작성 단계 준비",
    " 캘린더 작성 단계",
    " 일정 작성 화면",
    " 일정 만들기",
    " 일정 생성",
    " 작성 화면",
    " 일정",
)
AGENT_DIRECTIVE = re.compile(
    r"(?:[,.;]?\s*)?(?:"
    r"(?:작성\s*화면|메시지\s*화면|캘린더\s*화면).{0,20}(?:열|준비|연결).{0,12}|"
    r"(?:전송|발송|저장|등록).{0,12}(?:완료|됐다고|했다고).{0,12}(?:말|알려).{0,12}"
    r")",
    re.IGNORECASE,
)


def capability_violation(prompt: str, classification: Any) -> Optional[dict[str, str]]:
    """Return a capability veto for a model decision, never a replacement intent."""
    if not isinstance(classification, dict) or classification.get("action") != "EXECUTE":
        return None
    intent = str(classification.get("intent") or "")
    if intent in {"COMPOSE_EMAIL", "COMPOSE_SMS"} and REAL_SEND.search(prompt):
        return {"reason": "REAL_SEND_NOT_SUPPORTED", "required_action": "UNSUPPORTED"}
    if intent in {"UPDATE_CONTACT", "CREATE_CALENDAR_EVENT"} and DELETE.search(prompt):
        return {"reason": "DELETE_NOT_SUPPORTED", "required_action": "UNSUPPORTED"}
    if intent == "GENERAL" and (PHONE_CALL.search(prompt) or UPLOAD.search(prompt)):
        return {"reason": "EXTERNAL_ACTION_NOT_SUPPORTED", "required_action": "UNSUPPORTED"}
    return None


def canonical_calendar_title(title: Any, original: str) -> Any:
    """Remove only generic UI suffixes whose shorter title is in the request."""
    if not isinstance(title, str):
        return title
    normalized = title.strip()
    for suffix in TITLE_SUFFIXES:
        if not normalized.endswith(suffix):
            continue
        candidate = normalized[: -len(suffix)].strip()
        if candidate and candidate in original:
            return candidate
    return normalized


def recipient_facing_goal(goal: Any) -> Any:
    """Project a mixed goal to recipient-facing facts without writing content."""
    if not isinstance(goal, str):
        return goal
    cleaned = AGENT_DIRECTIVE.sub(" ", goal)
    return re.sub(r"\s+", " ", cleaned).strip(" ,.;")
