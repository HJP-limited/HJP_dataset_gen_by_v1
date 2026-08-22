"""Ablation C: short prompt plus stricter app-compatible compose schema."""

from pathlib import Path
import sys
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hjp_tools_preset import (  # noqa: E402
    DryRunTool,
    _object_schema,
    _open_compose,
    _string,
    tools as original_tools,
)


COMPOSE_SCHEMA = _object_schema(
    ["channel", "to", "body"],
    {
        "channel": _string("email 또는 sms", ["email", "sms"]),
        "to": _string("검증된 이메일 주소 또는 전화번호"),
        "subject": _string("이메일 제목. sms에서는 생략"),
        "body": _string("사용자가 확인할 자연스러운 한국어 초안"),
    },
)

tools = [tool for tool in original_tools if str(tool) != "open_compose"] + [
    DryRunTool(
        "open_compose",
        "검증된 수신자에게 초안을 채운 작성 화면을 엽니다. 전송은 사용자가 합니다.",
        COMPOSE_SCHEMA,
        _open_compose,
    )
]

system_instruction = (
    "You are a model that can do function calling with the following functions. "
    "한국어 명함 에이전트입니다. 연락처를 추측하지 마세요. 수신자 이름은 정보 부족이 아닙니다. "
    "이름으로 지정된 실행 요청은 이메일이나 번호를 다시 묻지 말고 반드시 search_contacts와 get_contact로 조회하세요. "
    "정보가 부족하면 실행하지 말고 질문하세요. 사용자가 전달할 핵심 맥락이 있으면 추가 내용을 묻지 말고 "
    "자연스러운 한국어 이메일 제목과 본문 또는 문자 본문을 직접 작성하세요. "
    "작성 화면을 연 것을 전송 완료라고 표현하지 마세요. 제공되지 않은 tool을 만들지 마세요. "
    "tool이 rejected 결과를 반환하면 allowed_next_tools 중 하나로 한 번만 수정하세요."
)
