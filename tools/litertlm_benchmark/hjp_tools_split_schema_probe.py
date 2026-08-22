"""Rejected schema probe: model-facing email/SMS tool separation."""

from pathlib import Path
import sys
from typing import Any, Mapping

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hjp_tools_preset import (  # noqa: E402
    DryRunTool,
    _open_compose,
    _object_schema,
    _string,
    tools as original_tools,
)


EMAIL_SCHEMA = _object_schema(
    ["to", "subject", "body"],
    {
        "to": _string("검증된 이메일 주소"),
        "subject": _string("자연스러운 한국어 이메일 제목"),
        "body": _string("사용자 의도를 반영한 자연스러운 한국어 이메일 본문"),
    },
)

SMS_SCHEMA = _object_schema(
    ["to", "body"],
    {
        "to": _string("검증된 전화번호"),
        "body": _string("사용자 의도를 반영한 자연스러운 한국어 문자 본문"),
    },
)


def _compose_email(param: Mapping[str, Any]) -> dict[str, Any]:
    mapped = {"channel": "email", **dict(param)}
    result = _open_compose(mapped)
    result["model_tool_name"] = "compose_email"
    result["mapped_tool_name"] = "open_compose"
    result["mapped_arguments"] = mapped
    return result


def _compose_sms(param: Mapping[str, Any]) -> dict[str, Any]:
    mapped = {"channel": "sms", **dict(param)}
    result = _open_compose(mapped)
    result["model_tool_name"] = "compose_sms"
    result["mapped_tool_name"] = "open_compose"
    result["mapped_arguments"] = mapped
    return result


tools = [tool for tool in original_tools if str(tool) != "open_compose"] + [
    DryRunTool(
        "compose_email",
        "검증된 이메일 주소로 제목과 본문을 채운 작성 화면을 엽니다. 전송은 사용자가 합니다.",
        EMAIL_SCHEMA,
        _compose_email,
    ),
    DryRunTool(
        "compose_sms",
        "검증된 전화번호로 본문을 채운 문자 작성 화면을 엽니다. 전송은 사용자가 합니다.",
        SMS_SCHEMA,
        _compose_sms,
    ),
]


system_instruction = (
    "You are a model that can do function calling with the following functions. "
    "한국어 명함 에이전트입니다. 연락처를 추측하지 마세요. 이름으로 지정된 실행 요청은 이메일이나 번호를 "
    "다시 묻지 말고 search_contacts와 get_contact로 조회하세요. "
    "정보가 부족하면 실행하지 말고 질문하세요. 사용자가 전달할 핵심 맥락이 있으면 추가 내용을 묻지 말고 "
    "자연스러운 한국어 이메일 제목과 본문 또는 문자 본문을 직접 작성하세요. "
    "작성 화면을 연 것을 전송 완료라고 표현하지 마세요. 제공되지 않은 tool을 만들지 마세요. "
    "tool이 rejected 결과를 반환하면 allowed_next_tools 중 하나로 한 번만 수정하세요."
)
