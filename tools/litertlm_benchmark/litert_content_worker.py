#!/usr/bin/env python3
"""One-process constrained email/SMS content generation with one repair."""

from __future__ import annotations

import argparse
import json
import re
import time
from pathlib import Path
from typing import Any

import litert_lm

from hjp_staged_schemas import SchemaTool, schema, string
from litert_staged_worker import run_stage


PLACEHOLDER_RE = re.compile(r"\[[^\]]+\]|client님|ooo님|당신의 이름", re.IGNORECASE)
FALSE_RE = re.compile(r"(?:전송|발송|저장|등록).{0,8}(?:완료|했습니다)|(?:보냈습니다)")
INSTRUCTION_RE = re.compile(r"작성\s*화면\s*열|전송했다고\s*말|저장\s*완료됐다고\s*말")
TIME_RE = re.compile(
    r"오늘|내일|모레|다음\s*주|이번\s*주|[월화수목금토일]요일|"
    r"[오전후]+\s*\d{1,2}시|\d{4}년\s*\d{1,2}월\s*\d{1,2}일"
)
SENTINEL = "HJP_CONTENT_RESULT_JSON="


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--channel", choices=("email", "sms"), required=True)
    parser.add_argument("--original", required=True)
    parser.add_argument("--goal", required=True)
    parser.add_argument("--recipient-name", default="")
    parser.add_argument("--recipient-company", default="")
    parser.add_argument("--recipient-title", default="")
    parser.add_argument("--tone", default="")
    parser.add_argument("--backend", choices=("cpu", "gpu"), default="cpu")
    parser.add_argument("--cache-dir", default="")
    return parser.parse_args()


def content_tool(channel: str) -> SchemaTool:
    properties: dict[str, Any] = {
        "body": string(description="UI 지시를 제외한 자연스러운 한국어 메시지 본문"),
    }
    required = ["body"]
    if channel == "email":
        properties["subject"] = string(description="간결한 한국어 이메일 제목")
        required.insert(0, "subject")
    return SchemaTool(
        "submit_email_content" if channel == "email" else "submit_sms_content",
        "수신자에게 전달할 한국어 메시지 내용만 제출합니다.",
        schema(required, properties),
    )


def validator(
    channel: str,
    original: str,
    goal: str,
    value: Any,
) -> list[str]:
    if not isinstance(value, dict):
        return ["content must be an object"]
    errors = []
    body = value.get("body")
    subject = value.get("subject")
    if not isinstance(body, str) or not body.strip():
        errors.append("body: required")
    if channel == "email" and (not isinstance(subject, str) or not subject.strip()):
        errors.append("subject: required for email")
    if channel == "sms" and subject is not None:
        errors.append("subject: forbidden for sms")
    combined = "\n".join(item for item in (subject, body) if isinstance(item, str))
    if PLACEHOLDER_RE.search(combined):
        errors.append("placeholder: forbidden")
    if FALSE_RE.search(combined):
        errors.append("false completion: forbidden")
    if INSTRUCTION_RE.search(combined):
        errors.append("agent UI instruction echo: forbidden")
    allowed = original + "\n" + goal
    for match in TIME_RE.finditer(combined):
        if match.group(0) not in allowed:
            errors.append(f"invented time fact: {match.group(0)}")
            break
    if channel == "sms" and isinstance(body, str) and len(body) > 240:
        errors.append("sms body: must be at most 240 Korean characters")
    return errors


def main() -> int:
    args = parse_args()
    if hasattr(litert_lm, "set_min_log_severity"):
        litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)
    backend = litert_lm.Backend.CPU() if args.backend == "cpu" else litert_lm.Backend.GPU()
    prompt = (
        f"채널: {args.channel}\n사용자 원문: {args.original}\n"
        f"수신자에게 전달할 내용 목표: {args.goal}\n"
        f"확정 수신자 이름: {args.recipient_name}\n회사: {args.recipient_company}\n"
        f"직함: {args.recipient_title}\n말투: {args.tone}\n"
        "원문의 '화면 열기·전송했다고 말하기·저장 완료라고 말하기'는 앱 행동 지시이므로 본문에서 제외하세요. "
        "수신자에게 전달할 내용만 schema로 제출하세요."
    )
    system = (
        "한국어 메시지 작성자입니다. 제공된 schema tool 하나만 호출하세요. "
        "사용자 원문과 content goal에 명시된 사실만 사용하세요. "
        "이름을 알면 자연스럽게 호칭하고, 모르면 호칭을 만들지 마세요. "
        "placeholder, client님, 원문에 없는 날짜·약속·계약·금액, 실제 전송/저장 완료 표현을 금지합니다. "
        "화면을 열거나 완료라고 말하라는 문장은 에이전트 지시이므로 절대 본문에 복사하지 마세요. "
        "이메일은 제목과 2~5문장 본문, 문자는 1~3문장의 짧은 본문을 제출하세요."
    )
    started = time.monotonic()
    result: dict[str, Any] = {}
    try:
        with litert_lm.Engine(
            str(args.model),
            backend=backend,
            cache_dir=args.cache_dir,
        ) as engine:
            result = run_stage(
                engine,
                prompt,
                system,
                content_tool(args.channel),
                lambda value: validator(args.channel, args.original, args.goal, value),
                True,
                True,
            )
    except Exception as exc:
        result = {"success": False, "value": None, "attempts": [], "worker_error": repr(exc)}
    result["duration_seconds"] = round(time.monotonic() - started, 6)
    print(SENTINEL + json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
