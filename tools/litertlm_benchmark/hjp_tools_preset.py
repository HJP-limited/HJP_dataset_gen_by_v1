"""Dry-run LiteRT-LM tools matching the Android app's current ToolContracts."""

from __future__ import annotations

import copy
from collections.abc import Mapping
from typing import Any, Callable

import litert_lm


CARD_ID = "card-kim-jiwon"
CARD_FIXTURE = {
    "card_id": CARD_ID,
    "name": "김지원",
    "name_en": "Jiwon Kim",
    "company": "테스트컴퍼니",
    "title": "팀장",
    "department": "영업팀",
    "industry": "IT",
    "location": "서울",
    "phone": "02-1234-5678",
    "mobile": "010-1234-5678",
    "email": "jiwon@example.com",
    "address": "서울특별시 중구 테스트로 1",
    "website": "https://example.com",
    "memo": "지난 상담에서 온디바이스 AI 도입을 논의함",
    "tags": ["상담", "AI"],
    "updated_at": "2026-07-24T09:00:00+09:00",
}

NO_EMAIL_FIXTURE = {
    **CARD_FIXTURE,
    "card_id": "card-no-email",
    "name": "이메일없는사람",
    "email": "",
}

NO_PHONE_FIXTURE = {
    **CARD_FIXTURE,
    "card_id": "card-no-phone",
    "name": "전화없는사람",
    "phone": "",
    "mobile": "",
}

GET_ERROR_FIXTURE = {
    **CARD_FIXTURE,
    "card_id": "card-get-error",
    "name": "상세오류사람",
}


def _string(description: str, enum: list[str] | None = None) -> dict[str, Any]:
    schema: dict[str, Any] = {"type": "string", "description": description}
    if enum is not None:
        schema["enum"] = enum
    return schema


def _object_schema(
    required: list[str],
    properties: dict[str, Any],
) -> dict[str, Any]:
    return {
        "type": "object",
        "additionalProperties": False,
        "properties": properties,
        "required": required,
    }


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

SEARCH_SCHEMA = _object_schema(
    ["query"],
    {
        "query": _string("명함을 찾기 위한 한국어 검색어"),
        "limit": {
            "type": "integer",
            "minimum": 1,
            "maximum": 10,
            "default": 5,
        },
    },
)

GET_SCHEMA = _object_schema(
    ["card_id"],
    {
        "card_id": _string("검색 결과에서 반환된 명함 ID"),
        "purpose": _string(
            "상세정보 조회 목적",
            ["display", "email", "sms", "calendar"],
        ),
    },
)

UPDATE_SCHEMA = _object_schema(
    ["card_id"],
    {
        "card_id": _string("수정할 명함 ID"),
        "updates": {
            "type": "object",
            "description": "변경할 필드와 새 값. 유지할 필드는 생략합니다.",
            "additionalProperties": False,
            "properties": {
                field: _string(field) for field in UPDATABLE_FIELDS
            },
        },
        "clear_fields": {
            "type": "array",
            "description": "값을 비울 필드 목록",
            "items": _string("비울 필드", UPDATABLE_FIELDS),
        },
    },
)

CALENDAR_SCHEMA = _object_schema(
    ["title", "start_time"],
    {
        "title": _string("일정 제목"),
        "start_time": _string(
            "기기 현지 시간대의 yyyy-MM-dd'T'HH:mm 시작 시각"
        ),
        "end_time": _string(
            "기기 현지 시간대의 yyyy-MM-dd'T'HH:mm 종료 시각. 생략 시 1시간 후"
        ),
        "location": _string("장소"),
        "description": _string("일정 메모"),
        "attendee_emails": {
            "type": "array",
            "items": {"type": "string", "format": "email"},
        },
    },
)

COMPOSE_SCHEMA = _object_schema(
    ["channel", "to"],
    {
        "channel": _string("작성할 메시지 종류", ["email", "sms"]),
        "to": _string("이메일 주소 또는 전화번호"),
        "subject": _string("이메일 제목"),
        "body": _string(
            "사용자가 확인할 초안 본문. 생략하면 빈 본문으로 작성 화면을 엽니다."
        ),
    },
)

DATETIME_SCHEMA = _object_schema(
    [],
    {
        "timezone": _string(
            "선택 IANA timezone ID. 예: Asia/Seoul. 생략 시 기기 기본 timezone"
        )
    },
)


def _received(param: Mapping[str, Any]) -> dict[str, Any]:
    return copy.deepcopy(dict(param))


def _search_contacts(param: Mapping[str, Any]) -> dict[str, Any]:
    query = str(param.get("query", ""))
    normalized = query.casefold()
    if "검색오류" in query:
        return {
            "dry_run": True,
            "received_arguments": _received(param),
            "status": "error",
            "error": "fixture_search_failure",
            "results": [],
            "count": 0,
            "engine": "benchmark_fixture",
        }
    cards = []
    if "이동명이인" in query:
        cards = [
            {**CARD_FIXTURE, "card_id": "card-duplicate-1", "name": "이동명이인"},
            {**CARD_FIXTURE, "card_id": "card-duplicate-2", "name": "이동명이인"},
        ]
    elif "이메일없는" in query:
        cards = [NO_EMAIL_FIXTURE]
    elif "전화없는" in query:
        cards = [NO_PHONE_FIXTURE]
    elif "상세오류" in query:
        cards = [GET_ERROR_FIXTURE]
    elif (
        "김지원" in query
        or "테스트컴퍼니" in query
        or "jiwon" in normalized
    ):
        cards = [CARD_FIXTURE]
    results = []
    for card in cards:
        results.append(
            {
                "card_id": card["card_id"],
                "name": card["name"],
                "company": card["company"],
                "title": card["title"],
                "location": card["location"],
                "score": 1.0,
            }
        )
    return {
        "dry_run": True,
        "received_arguments": _received(param),
        "results": results,
        "count": len(results),
        "engine": "benchmark_fixture",
    }


def _get_contact(param: Mapping[str, Any]) -> dict[str, Any]:
    card_id = param.get("card_id")
    if card_id == "card-get-error":
        return {
            "dry_run": True,
            "received_arguments": _received(param),
            "found": False,
            "status": "error",
            "error": "fixture_get_failure",
        }
    fixtures = {
        CARD_ID: CARD_FIXTURE,
        "card-no-email": NO_EMAIL_FIXTURE,
        "card-no-phone": NO_PHONE_FIXTURE,
        "card-duplicate-1": {**CARD_FIXTURE, "card_id": "card-duplicate-1", "name": "이동명이인"},
        "card-duplicate-2": {**CARD_FIXTURE, "card_id": "card-duplicate-2", "name": "이동명이인"},
    }
    card = fixtures.get(str(card_id))
    found = card is not None
    result: dict[str, Any] = {
        "dry_run": True,
        "received_arguments": _received(param),
        "found": found,
    }
    if found:
        result.update(copy.deepcopy(card))
    else:
        result["error"] = "contact_not_found"
    return result


def _update_business_card(param: Mapping[str, Any]) -> dict[str, Any]:
    before = copy.deepcopy(CARD_FIXTURE)
    after = copy.deepcopy(before)
    updates = param.get("updates")
    if isinstance(updates, Mapping):
        for key, value in updates.items():
            if key in UPDATABLE_FIELDS:
                after[key] = value
    clear_fields = param.get("clear_fields")
    if isinstance(clear_fields, list):
        for key in clear_fields:
            if key in UPDATABLE_FIELDS:
                after[key] = ""
    return {
        "dry_run": True,
        "received_arguments": _received(param),
        "executed": False,
        "requires_user_confirmation": True,
        "before": before,
        "after": after,
    }


def _create_calendar_event(param: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "dry_run": True,
        "received_arguments": _received(param),
        "opened": False,
        "destination": "calendar",
        "requires_user_confirmation": True,
        "message": "벤치마크이므로 캘린더 작성 화면을 실제로 열지 않았습니다.",
    }


def _open_compose(param: Mapping[str, Any]) -> dict[str, Any]:
    if param.get("to") == "compose-error@example.com":
        return {
            "dry_run": True,
            "received_arguments": _received(param),
            "opened": False,
            "destination": str(param.get("channel", "")),
            "status": "error",
            "error": "fixture_compose_failure",
            "requires_user_confirmation": True,
            "message": "작성 화면을 열지 못했습니다.",
        }
    return {
        "dry_run": True,
        "received_arguments": _received(param),
        "opened": False,
        "destination": str(param.get("channel", "")),
        "requires_user_confirmation": True,
        "message": "벤치마크이므로 작성 화면을 열거나 전송하지 않았습니다.",
    }


def _get_current_datetime(param: Mapping[str, Any]) -> dict[str, Any]:
    timezone = str(param.get("timezone", "Asia/Seoul"))
    return {
        "dry_run": True,
        "received_arguments": _received(param),
        "date": "2026-07-24",
        "time": "09:00:00",
        "datetime": "2026-07-24T09:00:00+09:00",
        "timezone": timezone,
        "epoch_millis": 1784851200000,
        "utc_offset": "+09:00",
    }


class DryRunTool(litert_lm.Tool):
    """LiteRT-LM Tool with an app-matching schema and side-effect-free executor."""

    def __init__(
        self,
        name: str,
        description: str,
        parameters: dict[str, Any],
        executor: Callable[[Mapping[str, Any]], dict[str, Any]],
    ) -> None:
        self.name = name
        self.description = description
        self.parameters = parameters
        self.executor = executor

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
        return self.executor(param)

    def __str__(self) -> str:
        return self.name


tools = [
    DryRunTool(
        "search_contacts",
        "이름, 회사, 직함, 지역, 업종, 메모를 기준으로 로컬 명함을 검색합니다.",
        SEARCH_SCHEMA,
        _search_contacts,
    ),
    DryRunTool(
        "get_contact",
        "명함 ID로 선택한 명함의 전화번호, 이메일 등 상세정보를 조회합니다.",
        GET_SCHEMA,
        _get_contact,
    ),
    DryRunTool(
        "update_business_card",
        "로컬 명함의 일부 필드를 수정하거나 비웁니다. 반드시 대상 명함을 검색/조회로 특정한 뒤 사용하세요.",
        UPDATE_SCHEMA,
        _update_business_card,
    ),
    DryRunTool(
        "create_calendar_event",
        "캘린더 앱의 일정 작성 화면을 열고 내용을 미리 채웁니다. 저장은 사용자가 합니다.",
        CALENDAR_SCHEMA,
        _create_calendar_event,
    ),
    DryRunTool(
        "open_compose",
        "이메일 또는 문자 작성 화면을 열고 초안을 미리 채웁니다. 전송은 사용자가 합니다.",
        COMPOSE_SCHEMA,
        _open_compose,
    ),
    DryRunTool(
        "get_current_datetime",
        "기기 또는 지정 timezone 기준 현재 날짜와 시각을 반환합니다. 오늘, 내일, 다음 주 같은 상대 날짜를 절대 시각으로 바꾸기 전에 사용하세요.",
        DATETIME_SCHEMA,
        _get_current_datetime,
    ),
]


system_instruction = (
    "You are a model that can do function calling with the following functions. "
    "당신은 Android 기기 안에서 동작하는 HJP 한국어 명함 에이전트의 Mac dry-run 벤치마크 모델입니다. "
    "다음 규칙은 필수입니다. "
    "1) 제공된 native tool만 사용하고 존재하지 않는 tool을 만들지 마세요. "
    "2) open_compose를 호출할 때는 모델이 사용자의 의도와 맥락을 반영한 자연스러운 한국어 body를 직접 생성하여 반드시 비어 있지 않은 body argument로 넣으세요. 이메일은 비어 있지 않은 subject도 반드시 넣으세요. schema에서 선택 필드여도 작성 요청에서는 subject와 body를 생략하면 실패입니다. 본문에 지시문, placeholder, 작성 절차를 넣지 마세요. "
    "예시: 'test@example.com에게 안녕하세요라고 메일 작성'은 open_compose의 channel='email', to='test@example.com', subject='인사드립니다', body='안녕하세요.'를 모두 채워 호출합니다. "
    "3) 이름으로 이메일·문자·일정을 요청하면 search_contacts, get_contact 순서로 실제 주소나 번호를 확인한 뒤 다음 tool을 호출하세요. 이름을 이메일 주소나 전화번호처럼 사용하지 마세요. "
    "4) get_current_datetime은 현재 시각 요청 또는 오늘·내일·다음 주 같은 상대 날짜 계산에만 사용하세요. 상대 날짜 일정은 그 결과를 절대 시각으로 바꾼 후 create_calendar_event를 호출하세요. "
    "5) 일반 대화·글쓰기 조언·지원하지 않는 삭제·전화·날씨 요청에는 tool을 호출하지 마세요. 필수 수신자·일정 시각·수정 내용이 없거나 이메일 형식이 잘못되면 tool을 호출하지 말고 추가 질문 또는 안전한 거절을 하세요. "
    "6) 연락처 검색 결과가 없으면 후속 tool을 호출하지 마세요. "
    "모든 tool은 dry-run이며 외부 화면을 열거나 전송, 저장, 수정하지 않습니다. "
    "최종 답변에서 메일이나 문자를 보냈다거나 일정 저장 또는 명함 수정이 완료됐다고 말하지 마세요."
)
