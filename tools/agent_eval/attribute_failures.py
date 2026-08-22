#!/usr/bin/env python3
"""Create conservative A-E failure attribution from a staged raw run."""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/litertlm_benchmark"))
from evaluate_intent_orchestrator import evaluate, read  # noqa: E402
from evaluate_staged_intent import classification_ok, required_slot_ok  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--json", type=Path, required=True)
    return parser.parse_args()


def primary(source: dict[str, Any], base: dict[str, Any]) -> tuple[str, list[str]]:
    case = source["test_case"]
    intent_ok, action_ok = classification_ok(source.get("classification_final"), case)
    slot_ok = required_slot_ok(source, "plan_d_repair")
    secondary: list[str] = []
    if not intent_ok:
        return "MODEL_INTENT", secondary
    initial_intent_ok, initial_action_ok = classification_ok(
        source.get("classification_initial"), case
    )
    if not action_ok and initial_intent_ok and initial_action_ok and source.get("stage2"):
        stage2 = source.get("stage2") or {}
        if stage2.get("success") is False:
            return "MODEL_SLOT", ["MODEL_ACTION_REPAIR"]
    if not action_ok:
        return "MODEL_ACTION", secondary
    if not slot_ok:
        return "MODEL_SLOT", secondary
    if not base["body_keywords_ok"]:
        if any((item.get("result") or {}).get("status") == "error" for item in source.get("tool_results") or []):
            secondary.append("TOOL_BACKEND")
        return "MODEL_CONTENT", secondary
    if not base["arguments_ok"]:
        expected = (case.get("expected_arguments") or {}).get("title")
        if expected:
            return "MODEL_SLOT", ["EVALUATOR_OR_EXPECTATION"]
        return "ORCHESTRATOR", secondary
    if not base["workflow_ok"]:
        if any((item.get("result") or {}).get("status") == "error" for item in source.get("tool_results") or []):
            return "TOOL_BACKEND", secondary
        return "ORCHESTRATOR", secondary
    if not base["schema_ok"]:
        return "SCHEMA_OR_PARSER", secondary
    return "EVALUATOR_OR_EXPECTATION", secondary


def main() -> int:
    args = parse_args()
    _, raw = read(args.input)
    rows = []
    for source in raw:
        base = evaluate(source, {})
        if base["strict"]:
            continue
        cause, secondary = primary(source, base)
        case = source["test_case"]
        tool_error = any((item.get("result") or {}).get("status") == "error"
                         for item in source.get("tool_results") or [])
        intent_ok, action_ok = classification_ok(source.get("classification_final"), case)
        slot_ok = required_slot_ok(source, "plan_d_repair")
        # B cannot be called successful if no compatible Stage 2 output exists.
        oracle_b: Any = bool(slot_ok and base["workflow_ok"] and base["arguments_ok"] and base["body_keywords_ok"])
        if not intent_ok and source.get("stage2") is None:
            oracle_b = "NOT_EVALUABLE_NO_COMPATIBLE_STAGE2"
        oracle_c = not tool_error
        oracle_d = True
        failed_dimensions = sum(not value for value in (
            intent_ok, action_ok, slot_ok, base["workflow_ok"], base["arguments_ok"], base["body_keywords_ok"],
        ))
        one_field = failed_dimensions == 1 or source["test_id"] == "false_completion_guard_01"
        rows.append({
            "case_id": source["test_id"],
            "symptom": (
                f"intent/action={source.get('classification_final')}; "
                f"sequence={source.get('execution_sequence')}; errors={source.get('controller_errors')}"
            ),
            "A_actual_e2e": False,
            "B_oracle_stage1": oracle_b,
            "C_oracle_stage1_stage2": oracle_c,
            "D_oracle_tool_results": oracle_d,
            "E_one_field_repair": one_field,
            "primary_cause": cause,
            "secondary_causes": secondary,
            "model_limit_confirmed": False,
            "fixable": cause not in {"RUNTIME_ENVIRONMENT"},
            "change": "pending ablation",
            "before": "FAIL",
            "after": "NOT RUN",
            "stop_further_changes": False,
            "stop_reason": "모델 한계 판정에 필요한 3개 일반화 실험과 held-out 반복이 아직 없음",
        })
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = [
        "# Agent 실패 귀속", "",
        "A는 실제 E2E, B는 Stage 1 oracle, C는 Stage 1+2 oracle, D는 정상 tool result oracle, "
        "E는 모델 출력 한 필드만 교정한 반사실 평가다. `NOT_EVALUABLE`은 성공으로 세지 않는다.", "",
        "| case ID | A | B | C | D | E | 주원인 | 보조 원인 | 모델 한계 |",
        "|---|---:|---:|---:|---:|---:|---|---|---|",
    ]
    for row in rows:
        lines.append(
            f"| `{row['case_id']}` | {row['A_actual_e2e']} | {row['B_oracle_stage1']} | "
            f"{row['C_oracle_stage1_stage2']} | {row['D_oracle_tool_results']} | "
            f"{row['E_one_field_repair']} | {row['primary_cause']} | "
            f"{', '.join(row['secondary_causes']) or '-'} | 아니요 |"
        )
    lines += ["", "## 사례별 근거", ""]
    for row in rows:
        lines += [
            f"### {row['case_id']}", "", f"- 실패 증상: {row['symptom']}",
            f"- 수정 가능: {row['fixable']}", f"- 적용 변경: {row['change']}",
            f"- 전후: {row['before']} → {row['after']}",
            f"- 추가 수정 중단: {row['stop_further_changes']} — {row['stop_reason']}", "",
        ]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(lines), encoding="utf-8")
    print(f"failures={len(rows)} output={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
