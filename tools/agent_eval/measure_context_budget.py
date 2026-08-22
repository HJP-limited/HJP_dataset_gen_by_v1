#!/usr/bin/env python3
"""Measure real Gemma 4 E2B token counts for agent prompt-context strategies.

The Kotlin side writes `fixtures/prompt_strategies.jsonl` (one record per
strategy/turn). This script tokenizes each prompt with the model's own
SentencePiece tokenizer so the context budget is measured, not guessed.
"""

from __future__ import annotations

import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path
from typing import Any

import litert_lm


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path, default=None)
    parser.add_argument("--fixture", type=Path, default=None)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def percentile(values: list[int], fraction: float) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round(fraction * (len(ordered) - 1))))
    return ordered[index]


def main() -> int:
    args = parse_args()
    if hasattr(litert_lm, "set_min_log_severity"):
        litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)

    summary: dict[str, Any] = {"model": str(args.model)}
    with litert_lm.Engine(
        str(args.model),
        backend=litert_lm.Backend.CPU(),
        cache_dir=str(args.cache_dir) if args.cache_dir else None,
    ) as engine:
        summary["engine"] = {
            "max_num_tokens": getattr(engine, "max_num_tokens", None),
            "bos_token_id": getattr(engine, "bos_token_id", None),
            "eos_token_ids": list(getattr(engine, "eos_token_ids", []) or []),
        }

        def count(text: str) -> int:
            return len(engine.tokenize(text))

        # Calibration: characters-per-token for Korean agent text. The Android app
        # cannot run this tokenizer inside a unit test, so the Kotlin estimator is
        # calibrated against these ratios.
        calibration = {}
        for name, sample in CALIBRATION_SAMPLES.items():
            tokens = count(sample)
            calibration[name] = {
                "chars": len(sample),
                "tokens": tokens,
                "chars_per_token": round(len(sample) / tokens, 4) if tokens else None,
            }
        summary["calibration"] = calibration

        if args.fixture and args.fixture.is_file():
            per_strategy: dict[str, list[int]] = defaultdict(list)
            growth: dict[str, dict[int, int]] = defaultdict(dict)
            records = []
            for line in args.fixture.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                record = json.loads(line)
                tokens = count(record["prompt"])
                record_out = {
                    "strategy": record["strategy"],
                    "turn": record["turn"],
                    "chars": len(record["prompt"]),
                    "tokens": tokens,
                }
                records.append(record_out)
                per_strategy[record["strategy"]].append(tokens)
                growth[record["strategy"]][record["turn"]] = tokens
            summary["records"] = records
            summary["per_strategy"] = {
                name: {
                    "turns": len(values),
                    "p50": percentile(values, 0.50),
                    "p95": percentile(values, 0.95),
                    "max": max(values),
                    "total": sum(values),
                    "mean": round(statistics.fmean(values), 1),
                    "first_turn": growth[name].get(1),
                    "last_turn": growth[name].get(max(growth[name])),
                }
                for name, values in sorted(per_strategy.items())
            }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary.get("engine", {}), ensure_ascii=False))
    for name, stats in summary.get("per_strategy", {}).items():
        print(f"{name}: {stats}")
    return 0


CALIBRATION_SAMPLES = {
    "korean_request": "김지원에게 지난 미팅 감사 메일 작성해줘. 정중한 말투로 부탁해.",
    "korean_answer": "‘김지원’ 명함을 찾았습니다. 비전글로벌 대표이사이고 이메일은 jiwon@example.com입니다.",
    "memory_block": (
        "[conversation_memory]\nschema_version: 2\ntopic: 김지원에게 감사 메일\n"
        "selected_contact:\n- card_id=C001 name=김지원 company=비전글로벌 title=대표이사 "
        "basis=SINGLE_RESULT provenance=TOOL_VERIFIED\nactions:\n- COMPLETED 김지원 명함 찾아줘\n"
    ),
    "tool_schema": (
        '{"name":"search_contacts","description":"저장된 명함을 검색합니다.",'
        '"parameters":{"type":"object","properties":{"query":{"type":"string"}},'
        '"required":["query"]}}'
    ),
}


if __name__ == "__main__":
    raise SystemExit(main())
