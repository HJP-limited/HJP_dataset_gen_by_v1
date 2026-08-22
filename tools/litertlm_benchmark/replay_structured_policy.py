#!/usr/bin/env python3
"""Replay final deterministic safety gates without rerunning model inference."""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import json
import re
from pathlib import Path
from typing import Any


INVALID_EMAIL_LIKE_RE = re.compile(
    r"(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+"
    r"(?:-at-|(?:\s+at\s+))[a-z0-9.-]+(?![a-z0-9.-])"
)
VALID_EMAIL_RE = re.compile(r"[^@\s]+@[^@\s]+\.[^@\s]+")
ALIASES = {
    "name": ("이름",),
    "name_en": ("영문 이름",),
    "company": ("회사", "회사명"),
    "department": ("부서",),
    "title": ("직함", "직급"),
    "industry": ("업종",),
    "location": ("지역", "위치"),
    "phone": ("전화",),
    "mobile": ("휴대폰", "전화번호"),
    "email": ("이메일", "메일 주소"),
    "address": ("주소",),
    "website": ("웹사이트",),
    "memo": ("메모",),
}


def rejection(row: dict[str, Any]) -> str | None:
    prompt = str(row.get("prompt") or "")
    plan = row.get("intent_plan") or {}
    if INVALID_EMAIL_LIKE_RE.search(prompt) and not VALID_EMAIL_RE.search(prompt):
        return "INVALID_EMAIL"
    if plan.get("intent") == "UPDATE_CONTACT":
        updates = plan.get("updates")
        if not isinstance(updates, dict) or not updates or any(
            not str(value).strip()
            or str(value) not in prompt
            or not any(alias in prompt for alias in ALIASES.get(field, (field,)))
            for field, value in updates.items()
        ):
            return "UPDATE_NOT_GROUNDED"
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    values = [
        json.loads(line)
        for line in args.input.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    output: list[dict[str, Any]] = []
    for value in values:
        value = copy.deepcopy(value)
        if value.get("record_type") == "run_metadata":
            value["architecture"] = "intent_workflow_orchestrator_policy_v2"
            value["policy_replay_source"] = str(args.input)
            value["policy_replayed_at_utc"] = dt.datetime.now(
                dt.timezone.utc
            ).isoformat()
        elif value.get("record_type") == "test_result":
            reason = rejection(value)
            if reason:
                value["policy_replay_source"] = str(args.input)
                value["pre_policy_execution_sequence"] = value.get(
                    "execution_sequence", []
                )
                value["policy_replay_reject_reason"] = reason
                value["orchestrator_tool_calls"] = []
                value["tool_calls"] = []
                value["execution_sequence"] = []
                value["tool_results"] = []
                value["controller_errors"] = [
                    *(value.get("controller_errors") or []),
                    f"pre_execution_policy:{reason}",
                ]
                value["assistant_output"] = (
                    "올바른 이메일 주소가 필요합니다."
                    if reason == "INVALID_EMAIL"
                    else "수정할 명함 필드와 새 값을 구체적으로 알려주세요."
                )
                value["final_response"] = value["assistant_output"]
        output.append(value)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        "".join(json.dumps(value, ensure_ascii=False) + "\n" for value in output),
        encoding="utf-8",
    )
    print(args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
