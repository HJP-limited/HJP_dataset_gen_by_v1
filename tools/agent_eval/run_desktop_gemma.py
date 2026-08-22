#!/usr/bin/env python3
"""Runs frozen visible scenarios against the real Gemma 4 E2B artifact on macOS.

What this measures and what it does not:

* model gateway  : actual Gemma (`gemma-4-E2B-it.litertlm`, LiteRT-LM, CPU backend)
* assembly       : Python harness reproducing the production system instruction and tool catalog.
                   It is NOT the Kotlin REACT assembly; that path is an Android library and cannot
                   be driven from the desktop JVM without reimplementing the gateway.
* tool backend   : dry-run. Tool calls are recorded and answered from a fixed fixture. No compose
                   screen, no calendar entry and no card write happens anywhere.
* runtime        : macOS / Python

So a pass here says the model emitted the right call for the right target given the production
catalog. It says nothing about Android side effects, and must never be reported as an E2E result.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import time
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from typing import Any, Mapping

import litert_lm

ROOT = Path(__file__).resolve().parents[2]
# Historical results are read-only; a run gets a fresh directory. See output_policy.
from output_policy import resolve_output_dir, require_writable  # noqa: E402


class SchemaTool(litert_lm.Tool):
    def __init__(self, name: str, description: str, parameters: dict[str, Any]) -> None:
        self.name = name
        self.description = description
        self.parameters = parameters

    def __str__(self) -> str:
        return self.name

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
        return {"accepted": True, "value": dict(param)}


def load_catalog() -> tuple[str, list[SchemaTool]]:
    data = json.loads((RESULTS / "production_tool_catalog.json").read_text(encoding="utf-8"))
    tools = [SchemaTool(t["name"], t["description"], t["parameters"]) for t in data["tools"]]
    return data["system_instruction"], tools


def first_tool_call(response: Any) -> dict[str, Any] | None:
    if not isinstance(response, dict):
        return None
    calls = response.get("tool_calls")
    if not isinstance(calls, list) or not calls:
        return None
    function = calls[0].get("function") if isinstance(calls[0], dict) else None
    if not isinstance(function, dict):
        return None
    return {"name": function.get("name"), "arguments": function.get("arguments")}


def answer_text(response: Any) -> str:
    if not isinstance(response, dict):
        return ""
    content = response.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return " ".join(
            part.get("text", "") for part in content if isinstance(part, dict)
        )
    return ""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default=str(ROOT / "models/gemma-4-E2B-it.litertlm"))
    parser.add_argument("--scenarios", default=str(RESULTS / "gemma_scenarios.json"))
    parser.add_argument("--output-dir", default=None,
                        help="fresh directory for this run; defaults to a new run directory")
    parser.add_argument("--out", default=None,
                        help="output file name inside --output-dir")
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()

    scenario_path = Path(args.scenarios)
    scenario_bytes = scenario_path.read_bytes()
    scenarios = json.loads(scenario_bytes)["scenarios"]
    if args.limit:
        scenarios = scenarios[: args.limit]

    system, tools = load_catalog()
    model_path = Path(args.model)
    started = time.time()
    engine = litert_lm.Engine(str(model_path), backend=litert_lm.Backend.CPU())

    rows: list[dict[str, Any]] = []
    for scenario in scenarios:
        for attempt in range(1, scenario.get("attempts", 1) + 1):
            t0 = time.time()
            try:
                with engine.create_conversation(
                    tools=tools,
                    automatic_tool_calling=False,
                    system_message=system,
                    sampler_config=litert_lm.SamplerConfig(
                        top_k=1, top_p=1.0, temperature=0.0, seed=42
                    ),
                ) as conversation:
                    response = conversation.send_message(scenario["prompt"])
                call = first_tool_call(response)
                text = answer_text(response)
                error = None
            except Exception as exc:  # noqa: BLE001 - recorded, never hidden
                call, text, error = None, "", f"{type(exc).__name__}: {exc}"
            elapsed = round(time.time() - t0, 2)

            expected_tool = scenario.get("expected_tool")
            tool_ok = (call or {}).get("name") == expected_tool if expected_tool else call is None
            arg_ok = True
            for key, want in (scenario.get("expected_args") or {}).items():
                got = (call or {}).get("arguments", {}).get(key)
                if want not in str(got):
                    arg_ok = False
            rows.append(
                {
                    "id": scenario["id"],
                    "category": scenario["category"],
                    "attempt": attempt,
                    "prompt": scenario["prompt"],
                    "expected_tool": expected_tool,
                    "actual_tool": (call or {}).get("name"),
                    "actual_arguments": (call or {}).get("arguments"),
                    "answer_excerpt": text[:400],
                    "tool_match": tool_ok,
                    "argument_match": arg_ok,
                    "pass": bool(tool_ok and arg_ok),
                    "seconds": elapsed,
                    "error": error,
                }
            )
            print(
                f"{scenario['id']} a{attempt}: expected={expected_tool} "
                f"actual={(call or {}).get('name')} ok={tool_ok and arg_ok} {elapsed}s",
                flush=True,
            )

    unique = {row["id"] for row in rows}
    payload = {
        "axes": {
            "model_gateway": "actual Gemma (gemma-4-E2B-it.litertlm)",
            "assembly": "python harness over the exported production catalog (NOT Kotlin REACT)",
            "tool_backend": "dry-run; tool calls recorded, no side effect anywhere",
            "runtime": "macos_python_litert_lm_cpu",
        },
        "model": {
            "path": model_path.name,
            "size_bytes": model_path.stat().st_size,
            "sha256": "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        },
        "scenario_file_sha256": hashlib.sha256(scenario_bytes).hexdigest(),
        "sampling": {"top_k": 1, "top_p": 1.0, "temperature": 0.0, "seed": 42},
        "unique_scenarios": len(unique),
        "total_attempts": len(rows),
        "passed_attempts": sum(1 for row in rows if row["pass"]),
        "wall_clock_seconds": round(time.time() - started, 1),
        "by_category": {
            category: {
                "attempts": sum(1 for row in rows if row["category"] == category),
                "passed": sum(1 for row in rows if row["category"] == category and row["pass"]),
            }
            for category in sorted({row["category"] for row in rows})
        },
        "results": rows,
    }
    Path(args.out).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        f"unique={len(unique)} attempts={len(rows)} "
        f"passed={payload['passed_attempts']} in {payload['wall_clock_seconds']}s"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
