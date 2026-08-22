#!/usr/bin/env python3
"""Compare three targeted Stage-1 repair messages on capability violations."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/litertlm_benchmark"))

import litert_lm  # noqa: E402
from hjp_staged_schemas import stage1_tool, validate_stage1  # noqa: E402
from litert_staged_worker import STAGE1_SYSTEM, run_stage  # noqa: E402


CASES = [
    ("send_email", "test@example.com에게 확인 메일을 지금 바로 실제로 전송해줘.", "COMPOSE_EMAIL"),
    ("send_sms", "010-1234-5678에게 확인 문자를 자동 전송해줘.", "COMPOSE_SMS"),
    ("delete_calendar", "2026년 8월 8일 일정을 캘린더에서 삭제해줘.", "CREATE_CALENDAR_EVENT"),
]
VARIANTS = {
    "policy_feedback": (
        "원본 사용자 요청: {prompt}\n확정 intent: {intent}\n"
        "앱 capability 검사 결과 이 동작은 지원하지 않습니다. intent를 유지하고 action=UNSUPPORTED만 제출하세요."
    ),
    "allowed_value": (
        "원본 사용자 요청: {prompt}\nintent는 {intent}로 확정됐습니다. "
        "지원하지 않는 실행이므로 허용 action은 UNSUPPORTED 하나뿐입니다. submit_action을 호출하세요."
    ),
    "counterfactual": (
        "원본 사용자 요청: {prompt}\n확정 intent: {intent}\n"
        "작성 화면 열기는 EXECUTE지만 실제 전송·자동 전송·삭제는 앱 capability 밖이므로 UNSUPPORTED입니다. "
        "이 요청의 action을 다시 제출하세요."
    ),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, default=ROOT / "models/gemma-4-E2B-it.litertlm")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = []
    with litert_lm.Engine(str(args.model), backend=litert_lm.Backend.CPU(), cache_dir=str(args.model.parent)) as engine:
        for variant, template in VARIANTS.items():
            for identifier, prompt, intent in CASES:
                result = run_stage(
                    engine,
                    template.format(prompt=prompt, intent=intent),
                    STAGE1_SYSTEM,
                    stage1_tool(),
                    validate_stage1,
                    False,
                    True,
                )
                value = result.get("value") or {}
                rows.append({
                    "variant": variant, "case": identifier, "prompt": prompt,
                    "expected": {"intent": intent, "action": "UNSUPPORTED"},
                    "result": result,
                    "passed": value.get("intent") == intent and value.get("action") == "UNSUPPORTED",
                })
                print(f"{variant}/{identifier}: {value}", flush=True)
    payload = {
        "model": str(args.model), "runtime": "litert-lm 0.14.0", "backend": "cpu",
        "variants": {name: sum(row["passed"] for row in rows if row["variant"] == name) for name in VARIANTS},
        "rows": rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
