#!/usr/bin/env python3
"""One-process LiteRT-LM Stage 1/Stage 2 constrained inference worker."""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path
from typing import Any

import litert_lm

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "agent_eval"))
from agent_policies import capability_violation

from hjp_staged_schemas import (
    ACTIONS,
    INTENTS,
    slot_tool,
    stage1_tool,
    validate_slots,
    validate_stage1,
)


STAGE1_SYSTEM = (
    "한국어 사용자 요청 하나를 분류합니다. submit_action만 호출합니다. "
    "수신자가 있고 '메일/문자 작성해줘·써줘'이면 작성 화면 실행 요청이므로 반드시 해당 COMPOSE intent와 EXECUTE입니다. "
    "검색·상세 보기·일정 만들기·명함 수정·현재시각 요청도 해당 intent와 EXECUTE입니다. "
    "'예시·방법·설명' 또는 '먼저 보여줘·화면은 열지 마'이면 GENERAL과 ANSWER 또는 PREVIEW입니다. "
    "수신자·일정 시각·수정값 등 실행 필수 정보가 없거나 직접 주소 형식이 틀리면 해당 intent와 CLARIFY입니다. "
    "일정 생성은 날짜 표현과 시간 표현이 둘 다 있을 때만 EXECUTE이고 하나라도 없으면 반드시 CLARIFY입니다. "
    "삭제·전화 걸기·날씨·실제 자동 전송은 관련 intent 또는 GENERAL과 UNSUPPORTED입니다. "
    "초안이라는 단어만으로 PREVIEW로 분류하지 말고 실행 금지나 보기 요청이 명시됐는지 확인하세요. "
    "대조: 'test@example.com에게 감사 메일 작성해줘'=COMPOSE_EMAIL/EXECUTE, "
    "'감사 이메일 예시를 보여줘'=GENERAL/ANSWER, "
    "'메일 내용을 먼저 보여줘. 화면은 열지 마'=GENERAL/PREVIEW, "
    "'지난 미팅 감사 이메일 작성해줘'=COMPOSE_EMAIL/CLARIFY(수신자 없음), "
    "'내일 고객 미팅 일정 만들어줘'=CREATE_CALENDAR_EVENT/CLARIFY(시간 없음), "
    "'김지원 명함 정보를 수정해줘'=UPDATE_CONTACT/CLARIFY(필드와 값 없음), "
    "'김지원 명함 삭제해줘'=UPDATE_CONTACT/UNSUPPORTED(삭제는 수정이 아님), "
    "'내일 서울 날씨 조회해줘'=GENERAL/UNSUPPORTED(내일이 있어도 시간 조회가 아님), "
    "'김지원에게 전화 걸어줘'=GENERAL/UNSUPPORTED, "
    "'명함 ID card-kim-jiwon 상세 정보'=VIEW_CONTACT/EXECUTE. "
    "'김지원 명함을 찾아줘'=SEARCH_CONTACT/EXECUTE, 상세를 보여 달라는 요청만 VIEW_CONTACT입니다. "
    "'실제로 전송해줘·자동 전송해줘'=해당 COMPOSE/UNSUPPORTED, "
    "'캘린더에서 삭제해줘'=CREATE_CALENDAR_EVENT/UNSUPPORTED입니다. "
    "이름에 '없는', '오류', '이메일없는'이 들어가도 존재 여부를 추측하지 말고 연락처 이름으로 EXECUTE하세요. "
    "user@, test-at-example, 공백이 든 주소와 짧거나 문자가 든 전화번호는 해당 COMPOSE/CLARIFY입니다. "
    "대조: '없는사람에게 감사 이메일 작성해줘'=COMPOSE_EMAIL/EXECUTE(없는사람은 제공된 이름), "
    "'test-at-example에게 감사 메일 작성해줘'=COMPOSE_EMAIL/CLARIFY(깨진 주소이지 이름이 아님). "
    "저수준 tool 이름이나 자연어 답변을 출력하지 마세요."
)
SLOT_SYSTEM = (
    "확정된 intent에 필요한 slot만 사용자 원문에서 그대로 추출합니다. "
    "값을 추측하거나 날짜를 계산하지 말고 제공된 schema tool 하나만 호출하세요. "
    "일정 원문에 사람 이름이 있으면 contact_name을 반드시 포함하고, 사람이 없을 때만 생략하세요."
    " CONTACT_NAME은 실제 사람 이름 표현에 사용하며 '없는사람·검색오류사람·이메일없는사람'도 CONTACT_NAME입니다. "
    "깨진 email/phone 모양의 문자열에는 CONTACT_NAME을 사용하지 마세요."
    " 연락처 이름은 조사 앞의 전체 원문을 보존하세요. 예: 이동명이인은 줄이지 말고 이동명이인입니다. "
    "일정 title은 핵심 이름만 쓰고 '일정', '작성 화면', '저장 완료' 같은 실행 표현은 제거하세요. "
    "compose content_goal에는 전달할 내용만 넣고 화면 열기·전송 완료라고 말하기 같은 에이전트 행동 지시는 제외하세요. "
    "예: '없는사람과 ... 미팅 일정'은 attendee_type=CONTACT_NAME, contact_name=없는사람입니다."
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--prompt", required=True)
    parser.add_argument("--backend", choices=("cpu", "gpu"), default="cpu")
    parser.add_argument("--repair", action="store_true")
    parser.add_argument("--constrained", action="store_true")
    parser.add_argument("--cache-dir", default="")
    return parser.parse_args()


def tool_arguments(response: Any, expected_name: str) -> tuple[dict[str, Any] | None, list[str]]:
    if not isinstance(response, dict):
        return None, ["response is not an object"]
    calls = response.get("tool_calls")
    if not isinstance(calls, list) or len(calls) != 1:
        return None, ["exactly one native tool call required"]
    function = calls[0].get("function") if isinstance(calls[0], dict) else None
    if not isinstance(function, dict) or function.get("name") != expected_name:
        return None, [f"expected tool={expected_name}"]
    arguments = function.get("arguments")
    if not isinstance(arguments, dict):
        return None, ["arguments must be an object"]
    return arguments, []


def run_stage(
    engine: litert_lm.Engine,
    prompt: str,
    system: str,
    tool: litert_lm.Tool,
    validator: Any,
    repair: bool,
    constrained: bool,
) -> dict[str, Any]:
    attempts = []
    with engine.create_conversation(
        tools=[tool],
        automatic_tool_calling=False,
        system_message=system,
        enable_constrained_decoding=constrained,
        sampler_config=litert_lm.SamplerConfig(
            top_k=1, top_p=1.0, temperature=0.0, seed=42
        ),
    ) as conversation:
        response = conversation.send_message(prompt)
        for attempt in range(2 if repair else 1):
            value, parse_errors = tool_arguments(response, str(tool))
            errors = parse_errors + (validator(value) if value is not None else [])
            attempts.append({
                "attempt": attempt + 1,
                "raw_response": response,
                "parsed": value,
                "errors": errors,
            })
            if value is not None and not errors:
                return {"success": True, "value": value, "attempts": attempts}
            if attempt == 0 and repair:
                consistency_error = any(
                    error in {"GENERAL cannot use EXECUTE", "ANSWER/PREVIEW requires GENERAL"}
                    for error in errors
                )
                fixed_intent = value.get("intent") if isinstance(value, dict) else None
                current_action = value.get("action") if isinstance(value, dict) else None
                if (
                    str(tool) == "submit_action"
                    and fixed_intent in INTENTS
                    and current_action in ACTIONS
                    and consistency_error
                ):
                    repair_instruction = (
                        f"원본 사용자 요청: {prompt}\n"
                        f"확정 intent: {fixed_intent}\n"
                        f"잘못된 action: {current_action}\n"
                        "intent는 바꾸지 말고 원본 요청에 맞는 action 하나만 고쳐 "
                        "같은 schema tool을 호출하세요."
                    )
                else:
                    repair_instruction = (
                        "원본 사용자 요청: " + prompt + "\n"
                        "잘못된 필드: " + "; ".join(errors) + "\n"
                        "위 오류만 수정해 같은 schema tool을 한 번 호출하세요."
                    )
                response = conversation.send_message(repair_instruction)
    return {"success": False, "value": None, "attempts": attempts}


def validate_provenance(prompt: str, value: Any) -> list[str]:
    if not isinstance(value, dict):
        return []
    errors = []
    for field in ("recipient_value", "contact_name"):
        candidate = value.get(field)
        if isinstance(candidate, str) and candidate.strip() and candidate.strip() not in prompt:
            errors.append(f"{field}: must appear verbatim in original user request")
    return errors


def validate_contextual_slots(intent: str, prompt: str, value: Any) -> list[str]:
    if intent != "CREATE_CALENDAR_EVENT" or not isinstance(value, dict):
        return []
    explicit_attendee = re.search(
        r"([가-힣]{2,8})(?:과|와)\s+(?=(?:\d{4}년|오늘|내일|모레|다음\s*주|이번\s*주))",
        prompt,
    )
    if explicit_attendee and (
        value.get("attendee_type") != "CONTACT_NAME"
        or value.get("contact_name") != explicit_attendee.group(1)
    ):
        return [
            "attendee_type/contact_name: explicit calendar attendee must be preserved "
            f"as CONTACT_NAME/{explicit_attendee.group(1)}"
        ]
    return []


def main() -> int:
    args = parse_args()
    started = time.monotonic()
    if hasattr(litert_lm, "set_min_log_severity"):
        litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)
    backend = litert_lm.Backend.CPU() if args.backend == "cpu" else litert_lm.Backend.GPU()
    result: dict[str, Any] = {
        "constrained_decoding": args.constrained,
        "repair_enabled": args.repair,
    }
    try:
        with litert_lm.Engine(
            str(args.model),
            backend=backend,
            cache_dir=args.cache_dir,
        ) as engine:
            stage1 = run_stage(
                engine,
                args.prompt,
                STAGE1_SYSTEM,
                stage1_tool(),
                validate_stage1,
                args.repair,
                args.constrained,
            )
            classification = stage1.get("value") if stage1.get("success") else None
            violation = capability_violation(args.prompt, classification)
            if violation is not None and isinstance(classification, dict):
                original_intent = str(classification.get("intent") or "")
                capability_repair = run_stage(
                    engine,
                    (
                        f"원본 사용자 요청: {args.prompt}\n"
                        f"확정 intent: {original_intent}\n"
                        f"앱 capability 검사 결과: {violation['reason']}. "
                        "이 동작은 지원하지 않습니다. intent를 유지하고 "
                        "action=UNSUPPORTED만 제출하세요."
                    ),
                    STAGE1_SYSTEM,
                    stage1_tool(),
                    lambda value: validate_stage1(value) + (
                        [] if isinstance(value, dict)
                        and value.get("intent") == original_intent
                        and value.get("action") == "UNSUPPORTED"
                        else ["capability repair must preserve intent and set action=UNSUPPORTED"]
                    ),
                    False,
                    args.constrained,
                )
                result["capability_action_repair"] = capability_repair
                if capability_repair.get("success"):
                    stage1["attempts"] += capability_repair.get("attempts") or []
                    stage1["value"] = capability_repair.get("value")
            result["stage1"] = stage1
            classification = stage1.get("value") if stage1.get("success") else None
            if isinstance(classification, dict) and classification.get("action") == "EXECUTE":
                intent = str(classification.get("intent"))
                tool = slot_tool(intent)
                if tool:
                    slot_prompt = (
                        f"확정 intent: {intent}\n사용자 원문: {args.prompt}\n"
                        "필요한 slot만 추출하세요."
                    )
                    result["stage2"] = run_stage(
                        engine,
                        slot_prompt,
                        SLOT_SYSTEM,
                        tool,
                        lambda value: (
                            validate_slots(intent, value)
                            + validate_provenance(args.prompt, value)
                            + validate_contextual_slots(intent, args.prompt, value)
                        ),
                        args.repair,
                        args.constrained,
                    )
                    if not result["stage2"].get("success"):
                        slot_attempts = result["stage2"].get("attempts") or []
                        missing_errors = (
                            slot_attempts[-1].get("errors") if slot_attempts
                            else ["required slots unavailable"]
                        )
                        semantic_repair = run_stage(
                            engine,
                            (
                                f"원본 사용자 요청: {args.prompt}\n"
                                f"확정 intent: {intent}\n"
                                "slot 단계가 원문에서 다음 필수 정보를 찾지 못했습니다: "
                                + "; ".join(missing_errors) + "\n"
                                "정보를 추측하지 말고 intent는 유지한 채 action만 다시 판정하세요. "
                                "실행 필수 정보가 원문에 없으면 CLARIFY입니다."
                            ),
                            STAGE1_SYSTEM,
                            stage1_tool(),
                            validate_stage1,
                            False,
                            args.constrained,
                        )
                        result["semantic_action_repair"] = semantic_repair
                        repaired_value = semantic_repair.get("value")
                        if (
                            semantic_repair.get("success")
                            and isinstance(repaired_value, dict)
                            and repaired_value.get("intent") == intent
                            and repaired_value.get("action") in {"CLARIFY", "UNSUPPORTED"}
                        ):
                            stage1["attempts"] += semantic_repair.get("attempts") or []
                            stage1["value"] = repaired_value
                else:
                    result["stage2"] = {
                        "success": True, "value": {}, "attempts": []
                    }
            else:
                result["stage2"] = None
    except Exception as exc:
        result["worker_error"] = repr(exc)
    result["duration_seconds"] = round(time.monotonic() - started, 6)
    print("HJP_RESULT_JSON=" + json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
