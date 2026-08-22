#!/usr/bin/env python3
"""Build a frozen, template-disjoint agent evaluation corpus.

The held-out reference file is intentionally separated from the public input file.
Run this once before changing agent behavior and record the emitted SHA-256 values.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"


SPLITS = {
    "development": {
        "names": ["서하린", "윤도현", "문채원", "배준서"],
        "email_domain": "dev-agent.example",
        "style": "직접적",
    },
    "validation": {
        "names": ["오세진", "한유림", "조태경", "임다은"],
        "email_domain": "val-agent.example",
        "style": "공손한 질문",
    },
    "held_out": {
        "names": ["노지후", "송예린", "권민재", "차수아"],
        "email_domain": "test-agent.example",
        "style": "간접적",
    },
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def case(
    split: str,
    number: int,
    category: str,
    prompt: str,
    intent: str,
    action: str,
    tools: list[str],
    arguments: dict[str, Any] | None = None,
    *,
    slots: dict[str, Any] | None = None,
    final_state: dict[str, Any] | None = None,
    body_requirements: list[str] | None = None,
    turns: list[str] | None = None,
    fixture: dict[str, Any] | None = None,
    notes: str = "",
) -> dict[str, Any]:
    return {
        "id": f"ae_{split[:3]}_{number:03d}",
        "benchmark_version": "agent-eval-v1",
        "split": split,
        "category": category,
        "prompt": prompt,
        "turns": turns or [prompt],
        "expected_stage_intent": intent,
        "expected_action": action,
        "expected_slots": slots or {},
        "expected_tools": tools,
        "minimum_required_tools": tools,
        "allowed_trajectories": [tools],
        "forbidden_tools": ["send_email", "send_sms", "delete_contact", "delete_calendar_event"],
        "expected_arguments": arguments or {},
        "expected_final_state": final_state or {
            "tool_sequence": tools,
            "must_not_mutate_without_confirmation": True,
        },
        "should_call_tool": bool(tools),
        "body_requirements": body_requirements or [],
        "fixture": fixture or {},
        "notes": notes,
    }


def diversify(split: str, text: str) -> str:
    """Use disjoint surface-form families outside the development split."""
    if split == "development":
        return text
    replacements = {
        "validation": [
            ("명함을 검색해 주세요", "연락처 목록에서 찾아볼 수 있을까요"),
            ("상세 정보를 보여 주세요", "세부 항목을 확인해 주실래요"),
            ("상세 정보를 보여 줘", "세부 항목을 확인해 줄래"),
            ("작성 화면을 열어 주세요", "내용을 채운 작성 창까지 준비해 주실래요"),
            ("문자 화면을 열어 줘", "메시지 작성 창을 준비해 줄래"),
            ("문자를 작성해 주세요", "보낼 문구를 채운 문자 창을 준비해 주실래요"),
            ("일정을 만들어 주세요", "캘린더 작성 단계까지 준비해 주실래요"),
            ("일정을 잡아 주세요", "캘린더에 넣을 화면을 준비해 주실래요"),
            ("수정해 주세요", "바꾸는 절차를 진행해 주실래요"),
            ("알려 주세요", "설명해 주실 수 있나요"),
            ("삭제해 주세요", "없애는 기능을 실행할 수 있나요"),
        ],
        "held_out": [
            ("명함을 검색해 주세요", "연락처에 등록되어 있는지 확인 부탁드립니다"),
            ("상세 정보를 보여 주세요", "카드의 세부 내용을 확인하고 싶습니다"),
            ("상세 정보를 보여 줘", "카드 세부 내용을 확인하고 싶어"),
            ("작성 화면을 열어 주세요", "수신자와 내용을 넣은 작성 단계로 이어 주세요"),
            ("문자 화면을 열어 줘", "메시지를 적어 둔 화면으로 이어 줘"),
            ("문자를 작성해 주세요", "문자 초안을 채운 화면으로 연결 부탁드립니다"),
            ("일정을 만들어 주세요", "캘린더에 입력할 수 있게 준비 부탁드립니다"),
            ("일정을 잡아 주세요", "캘린더 입력 화면으로 연결 부탁드립니다"),
            ("수정해 주세요", "해당 항목을 바꾸는 확인 단계로 진행 부탁드립니다"),
            ("알려 주세요", "확인해서 답변 부탁드립니다"),
            ("삭제해 주세요", "지우는 작업까지 처리 부탁드립니다"),
        ],
    }[split]
    value = text
    for old, new in replacements:
        value = value.replace(old, new)
    if value == text:
        value = (
            "업무 요청입니다. " + value
            if split == "validation"
            else "상황을 확인한 뒤 처리 부탁드립니다. " + value
        )
    return value


def build(split: str) -> list[dict[str, Any]]:
    cfg = SPLITS[split]
    names = cfg["names"]
    domain = cfg["email_domain"]
    rows: list[dict[str, Any]] = []

    def add(*args: Any, **kwargs: Any) -> None:
        values = list(args)
        values[1] = diversify(split, str(values[1]))
        if "turns" in kwargs:
            kwargs["turns"] = [diversify(split, turn) for turn in kwargs["turns"]]
            values[1] = kwargs["turns"][-1]
        rows.append(case(split, len(rows) + 1, *values, **kwargs))

    # Search/view/time: 8
    for name in names:
        add("single_tool", f"{name} 명함을 검색해 주세요.", "SEARCH_CONTACT", "EXECUTE",
            ["search_contacts"], slots={"contact_name": name}, fixture={"contact_name": name})
    add("single_tool", "명함 ID card-eval-210의 상세 정보를 보여 주세요.", "VIEW_CONTACT", "EXECUTE",
        ["get_contact"], {"card_id": "card-eval-210"},
        slots={"recipient_type": "CARD_ID", "recipient_value": "card-eval-210"})
    add("single_tool", "card-eval-315에 해당하는 명함을 조회해 줘.", "VIEW_CONTACT", "EXECUTE",
        ["get_contact"], {"card_id": "card-eval-315"},
        slots={"recipient_type": "CARD_ID", "recipient_value": "card-eval-315"})
    add("single_tool", "서울 기준 지금 날짜와 시간을 알려 주세요.", "GET_CURRENT_DATETIME", "EXECUTE",
        ["get_current_datetime"])
    add("single_tool", "현재 시각 확인 부탁해.", "GET_CURRENT_DATETIME", "EXECUTE",
        ["get_current_datetime"])

    # No-tool/preview: 5
    no_tools = [
        ("정중한 감사 이메일은 보통 어떻게 쓰나요?", "ANSWER"),
        ("업무 문자를 간결하게 쓰는 원칙을 설명해 주세요.", "ANSWER"),
        ("명함 관리가 중요한 이유를 알려 줘.", "ANSWER"),
        ("사과 메일 초안 예시만 보여 주고 작성 화면은 열지 마세요.", "PREVIEW"),
        ("회의 일정 제목을 잘 짓는 방법을 알려 주세요.", "ANSWER"),
    ]
    for prompt, action in no_tools:
        add("no_tool", prompt, "GENERAL", action, [])

    # Direct compose: 8
    for i in range(4):
        address = f"direct{i + 1}@{domain}"
        goal = ["협업 제안", "자료 검토 감사", "일정 변경 사과", "제품 문의 답변"][i]
        add("direct_email", f"{address}에게 {goal} 내용을 담아 정중한 메일 작성 화면을 열어 주세요.",
            "COMPOSE_EMAIL", "EXECUTE", ["open_compose"],
            {"channel": "email", "to": address},
            slots={"recipient_type": "EMAIL", "recipient_value": address, "content_goal": goal},
            body_requirements=goal.split())
    phones = ["010-7421-1101", "010-7421-2202", "010-7421-3303", "010-7421-4404"]
    for i, phone in enumerate(phones):
        goal = ["방문 감사", "도착 지연 안내", "자료 수신 확인", "다음 연락 약속"][i]
        add("direct_sms", f"{phone}로 {goal} 문자를 작성해서 문자 화면을 열어 줘.",
            "COMPOSE_SMS", "EXECUTE", ["open_compose"],
            {"channel": "sms", "to": phone},
            slots={"recipient_type": "PHONE", "recipient_value": phone, "content_goal": goal},
            body_requirements=goal.split())

    # Contact chains: 8. Fixtures are split-specific, preventing name leakage.
    for name in names:
        goal = "지난 상담 감사와 후속 자료 전달"
        add("contact_email_chain", f"{name}에게 {goal}에 관한 메일 작성 화면을 열어 주세요.",
            "COMPOSE_EMAIL", "EXECUTE", ["search_contacts", "get_contact", "open_compose"],
            {"channel": "email"},
            slots={"recipient_type": "CONTACT_NAME", "recipient_value": name, "content_goal": goal},
            body_requirements=["상담", "감사", "자료"], fixture={"contact_name": name, "count": 1})
    for name in reversed(names):
        goal = "미팅 감사와 다음 주 연락"
        add("contact_sms_chain", f"{name}님께 {goal} 내용을 문자로 작성해 주세요.",
            "COMPOSE_SMS", "EXECUTE", ["search_contacts", "get_contact", "open_compose"],
            {"channel": "sms"},
            slots={"recipient_type": "CONTACT_NAME", "recipient_value": name, "content_goal": goal},
            body_requirements=["미팅", "감사", "다음 주"], fixture={"contact_name": name, "count": 1})

    # Calendar: 8
    calendar_rows = [
        ("내일 오후 2시", "분기 검토", "2026-07-25T14:00"),
        ("다음 주 월요일 오전 9시", "기획 회의", "2026-07-27T09:00"),
        ("이번 주 금요일 오후 4시", "주간 회고", "2026-07-24T16:00"),
        ("2026년 8월 12일 오전 11시", "계약 검토", "2026-08-12T11:00"),
    ]
    for expression, title, start in calendar_rows:
        date_expr, time_expr = expression.rsplit(" ", 2)[0], " ".join(expression.rsplit(" ", 2)[1:])
        expected = ["create_calendar_event"] if expression.startswith("2026") else ["get_current_datetime", "create_calendar_event"]
        add("calendar", f"{expression}에 {title} 일정을 만들어 주세요.",
            "CREATE_CALENDAR_EVENT", "EXECUTE", expected,
            {"title": title, "start_time": start},
            slots={"title": title, "date_expression": date_expr, "time_expression": time_expr})
    for i, name in enumerate(names):
        expression, title, start = calendar_rows[i]
        date_expr, time_expr = expression.rsplit(" ", 2)[0], " ".join(expression.rsplit(" ", 2)[1:])
        prefix = [] if expression.startswith("2026") else ["get_current_datetime"]
        add("contact_calendar_chain", f"{name}과 {expression}에 {title} 일정을 잡아 주세요.",
            "CREATE_CALENDAR_EVENT", "EXECUTE",
            prefix + ["search_contacts", "get_contact", "create_calendar_event"],
            {"title": title, "start_time": start},
            slots={"title": title, "date_expression": date_expr, "time_expression": time_expr,
                   "contact_name": name}, fixture={"contact_name": name, "count": 1})

    # Updates: 4
    fields = [("회사", "company", "새봄테크"), ("부서", "department", "전략기획실"),
              ("직함", "title", "수석연구원"), ("메모", "memo", "컨퍼런스에서 만남")]
    for name, (label, field, value) in zip(names, fields):
        add("update_contact", f"{name} 명함에서 {label} 항목을 다음 값으로 수정해 주세요: {value}.",
            "UPDATE_CONTACT", "EXECUTE", ["search_contacts", "get_contact", "update_business_card"],
            slots={"contact_name": name, "update_field": field, "update_value": value},
            fixture={"contact_name": name, "count": 1})

    # Clarification: 6
    clarify = [
        ("지난 미팅 감사 메일을 작성해 주세요.", "COMPOSE_EMAIL"),
        ("도착 안내 문자를 작성해 줘.", "COMPOSE_SMS"),
        ("다음 주에 회의 일정을 잡아 주세요.", "CREATE_CALENDAR_EVENT"),
        (f"{names[0]} 명함 정보를 수정해 주세요.", "UPDATE_CONTACT"),
        ("user@ 에게 확인 메일을 작성해 주세요.", "COMPOSE_EMAIL"),
        ("010-12번으로 확인 문자를 작성해 주세요.", "COMPOSE_SMS"),
    ]
    for prompt, intent in clarify:
        add("clarification", prompt, intent, "CLARIFY", [])

    # Unsupported capability requests: 7
    unsupported = [
        (f"{names[1]} 명함을 삭제해 주세요.", "UPDATE_CONTACT"),
        (f"{names[2]}에게 전화를 걸어 주세요.", "GENERAL"),
        (f"notice@{domain}로 메일을 실제로 전송해 주세요.", "COMPOSE_EMAIL"),
        ("010-9999-8888로 문자를 자동 전송해 줘.", "COMPOSE_SMS"),
        ("내일 일정을 캘린더에서 삭제해 주세요.", "CREATE_CALENDAR_EVENT"),
        ("이번 주 날씨를 조회해 주세요.", "GENERAL"),
        ("모든 명함을 외부 서버로 업로드해 줘.", "GENERAL"),
    ]
    for prompt, intent in unsupported:
        add("unsupported", prompt, intent, "UNSUPPORTED", [])

    # Invalid recipients: 4
    invalids = [
        ("name-at-domain.example에게 안내 메일 작성해 줘.", "COMPOSE_EMAIL"),
        ("a b@example.com에게 안내 메일 작성해 줘.", "COMPOSE_EMAIL"),
        ("1234로 확인 문자 작성해 줘.", "COMPOSE_SMS"),
        ("010-ABCD-1234로 확인 문자 작성해 줘.", "COMPOSE_SMS"),
    ]
    for prompt, intent in invalids:
        add("invalid_argument", prompt, intent, "CLARIFY", [])

    # Retrieval edges: 4
    edge_names = [f"{names[0]}동명이인", f"{names[1]}미등록", f"{names[2]}메일없음", f"{names[3]}번호없음"]
    edge_specs = [
        (edge_names[0], "COMPOSE_EMAIL", ["search_contacts"], {"count": 2}),
        (edge_names[1], "COMPOSE_SMS", ["search_contacts"], {"count": 0}),
        (edge_names[2], "COMPOSE_EMAIL", ["search_contacts", "get_contact"], {"count": 1, "email": ""}),
        (edge_names[3], "COMPOSE_SMS", ["search_contacts", "get_contact"], {"count": 1, "mobile": ""}),
    ]
    for name, intent, tools, fixture_extra in edge_specs:
        channel = "메일" if intent == "COMPOSE_EMAIL" else "문자"
        add("retrieval_edge", f"{name}에게 감사 {channel}을 작성해 주세요.", intent, "EXECUTE", tools,
            slots={"recipient_type": "CONTACT_NAME", "recipient_value": name, "content_goal": "감사"},
            fixture={"contact_name": name, **fixture_extra})

    # Tool/recovery/stale-ID: 4
    add("tool_error", f"{names[0]}에게 오류 후에도 사과 메일 내용을 보존해 작성해 주세요.",
        "COMPOSE_EMAIL", "EXECUTE", ["search_contacts"],
        slots={"recipient_type": "CONTACT_NAME", "recipient_value": names[0], "content_goal": "사과"},
        fixture={"contact_name": names[0], "search_error": True})
    add("tool_error", f"{names[1]}에게 장애 안내 메일을 작성해 주세요.",
        "COMPOSE_EMAIL", "EXECUTE", ["search_contacts", "get_contact"],
        slots={"recipient_type": "CONTACT_NAME", "recipient_value": names[1], "content_goal": "장애 안내"},
        fixture={"contact_name": names[1], "detail_error": True})
    add("stale_id", "이전 검색에서 사라진 card-stale-77 명함으로 문자를 작성해 주세요.",
        "COMPOSE_SMS", "EXECUTE", [],
        slots={"recipient_type": "CARD_ID", "recipient_value": "card-stale-77"},
        final_state={"stale_id_blocked": True, "tool_sequence": []})
    add("tool_error", f"retry@{domain}에게 장애 안내 메일 작성 화면을 열어 주세요.",
        "COMPOSE_EMAIL", "EXECUTE", ["open_compose"],
        {"channel": "email", "to": f"retry@{domain}"},
        slots={"recipient_type": "EMAIL", "recipient_value": f"retry@{domain}", "content_goal": "장애 안내"},
        body_requirements=["장애", "안내"], fixture={"compose_error": True})

    # Multi-turn: 4 (separate session runner consumes turns; final prompt is not flattened).
    multi = [
        ([f"{names[0]} 명함 찾아줘", "그분 회사가 어디인지 보여 줘"], "VIEW_CONTACT", "EXECUTE", ["get_contact"]),
        ([f"{names[1]}씨 검색해 줘", "그 사람에게 상담 감사 문자 작성해 줘"], "COMPOSE_SMS", "EXECUTE", ["get_contact", "open_compose"]),
        ([f"{names[2]} 찾아줘", f"아니, {names[3]}에게 협업 메일 작성해 줘"], "COMPOSE_EMAIL", "EXECUTE", ["search_contacts", "get_contact", "open_compose"]),
        ([f"{names[0]} 검색해 줘", "번호 뒷자리 4312인 사람을 새로 찾아줘", "첫 번째 사람 명함 보여 줘"], "VIEW_CONTACT", "EXECUTE", ["get_contact"]),
    ]
    for turns, intent, action, tools in multi:
        add("multiturn", turns[-1], intent, action, tools, turns=turns,
            final_state={"tool_sequence": tools, "wrong_person_lookup": False, "stale_id_execution": False})

    split_only_general = {
        "development": "명함의 QR 코드를 활용하는 일반적인 방법을 설명해 줘.",
        "validation": "연락처를 업무 분야별로 정리하는 원칙이 궁금합니다.",
        "held_out": "네트워킹 뒤에 명함을 정리할 때 주의할 점은 무엇인가요?",
    }
    add("no_tool", split_only_general[split], "GENERAL", "ANSWER", [])

    assert len(rows) == 71, (split, len(rows))
    return rows


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DATA)
    args = parser.parse_args()
    output = args.output_dir
    all_public: list[dict[str, Any]] = []
    manifest: dict[str, Any] = {"benchmark_version": "agent-eval-v1", "splits": {}}
    for split in SPLITS:
        rows = build(split)
        public_rows = rows
        target = output / f"{split}.jsonl"
        if split == "held_out":
            target = output / "held_out_inputs.jsonl"
            public_rows = [
                {key: value for key, value in row.items() if not key.startswith("expected_") and key not in {
                    "minimum_required_tools", "allowed_trajectories", "forbidden_tools", "body_requirements"
                }}
                for row in rows
            ]
            write_jsonl(output / ".sealed/held_out_reference.jsonl", rows)
        write_jsonl(target, public_rows)
        all_public.extend(public_rows)
        manifest["splits"][split] = {"count": len(rows), "input": str(target)}
    write_jsonl(output / "all_inputs.jsonl", all_public)
    for path in sorted(output.rglob("*.jsonl")):
        manifest.setdefault("files", {})[str(path.relative_to(output))] = {
            "count": sum(1 for _ in path.open(encoding="utf-8")),
            "sha256": sha256(path),
        }
    manifest_path = output / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
