#!/usr/bin/env python3
"""Run Gemma Stage 1/Stage 2 extraction and the existing workflow controller."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import subprocess
import sys
import time
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from hjp_staged_schemas import validate_slots, validate_stage1
from run_benchmark import (
    DEFAULT_RESULTS,
    DEFAULT_TESTS,
    REPO_ROOT,
    read_jsonl,
    resolve_path,
    select_cases,
    sha256_file,
)
from run_intent_orchestrator_benchmark import (
    Controller,
    MODEL_DEFAULT,
    cli_version,
    resolve_cli,
    result_record,
)
import run_intent_orchestrator_benchmark as legacy_benchmark

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "agent_eval"))
from agent_policies import canonical_calendar_title, recipient_facing_goal


BENCHMARK_DIR = Path(__file__).resolve().parent
WORKER = BENCHMARK_DIR / "litert_staged_worker.py"
CONTENT_WORKER = BENCHMARK_DIR / "litert_content_worker.py"
DEFAULT_PYTHON = BENCHMARK_DIR / ".venv/bin/python"
SENTINEL = "HJP_RESULT_JSON="


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run two-stage constrained intent extraction on the existing cases."
    )
    parser.add_argument("--model", default=str(MODEL_DEFAULT))
    parser.add_argument("--model-name", default="gemma4-staged-intent-e")
    parser.add_argument("--backend", choices=("cpu", "gpu"), default="cpu")
    parser.add_argument("--python", default=str(DEFAULT_PYTHON))
    parser.add_argument("--cli")
    parser.add_argument("--tests-file", type=Path, default=DEFAULT_TESTS)
    parser.add_argument("--test-case", action="append", default=[])
    parser.add_argument("--category", action="append", default=[])
    parser.add_argument("--limit", type=int)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--timeout", type=float, default=240.0)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--no-constrained", action="store_true")
    parser.add_argument("--no-repair", action="store_true")
    return parser.parse_args()


def worker_command(
    python: Path,
    model: Path,
    args: argparse.Namespace,
    prompt: str,
) -> list[str]:
    command = [
        str(python),
        str(WORKER),
        "--model", str(model),
        "--prompt", prompt,
        "--backend", args.backend,
        "--cache-dir", str(model.parent),
    ]
    if not args.no_constrained:
        command.append("--constrained")
    if not args.no_repair:
        command.append("--repair")
    return command


def invoke_worker(command: list[str], timeout: float) -> dict[str, Any]:
    started = time.monotonic()
    try:
        completed = subprocess.run(
            command,
            text=True,
            capture_output=True,
            timeout=timeout,
            check=False,
        )
        run = {
            "command": command,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
            "return_code": completed.returncode,
            "duration_seconds": round(time.monotonic() - started, 6),
            "timed_out": False,
        }
    except subprocess.TimeoutExpired as exc:
        run = {
            "command": command,
            "stdout": exc.stdout or "",
            "stderr": exc.stderr or "",
            "return_code": None,
            "duration_seconds": round(time.monotonic() - started, 6),
            "timed_out": True,
        }
    payload = None
    for line in str(run["stdout"]).splitlines():
        if line.startswith(SENTINEL):
            try:
                payload = json.loads(line[len(SENTINEL):])
            except json.JSONDecodeError as exc:
                run["sentinel_parse_error"] = str(exc)
    run["staged_result"] = payload
    if payload is None and "sentinel_parse_error" not in run:
        run["sentinel_parse_error"] = "HJP_RESULT_JSON sentinel missing"
    return run


def attempt_value(stage: Any, final: bool) -> dict[str, Any] | None:
    if not isinstance(stage, dict):
        return None
    if final:
        return stage.get("value") if stage.get("success") else None
    attempts = stage.get("attempts") or []
    if not attempts:
        return {}
    first = attempts[0]
    return first.get("parsed") if not first.get("errors") else None


def plan_from(
    classification: dict[str, Any] | None,
    slots: dict[str, Any] | None,
    original_prompt: str = "",
) -> dict[str, Any] | None:
    if not classification:
        return None
    stage_intent = classification.get("intent")
    action = classification.get("action")
    if action != "EXECUTE":
        mapped = {
            "ANSWER": "ANSWER_ONLY",
            "PREVIEW": "ANSWER_ONLY",
            "CLARIFY": "CLARIFY",
            "UNSUPPORTED": "UNSUPPORTED",
        }.get(str(action))
        if mapped is None:
            return None
        return {
            "intent": mapped,
            "execute": False,
            "recipient_type": "NONE",
            "recipient_value": "",
            "stage_intent": stage_intent,
            "action": action,
        }
    if stage_intent == "GET_CURRENT_DATETIME":
        return {
            "intent": stage_intent,
            "execute": True,
            "recipient_type": "NONE",
            "recipient_value": "",
            "stage_intent": stage_intent,
            "action": action,
        }
    if not isinstance(slots, dict):
        return None
    plan: dict[str, Any] = {
        "intent": stage_intent,
        "execute": True,
        "recipient_type": slots.get("recipient_type", "NONE"),
        "recipient_value": slots.get("recipient_value", ""),
        "stage_intent": stage_intent,
        "action": action,
    }
    if stage_intent == "SEARCH_CONTACT":
        plan.update(recipient_type="CONTACT_NAME", recipient_value=slots.get("contact_name", ""))
    elif stage_intent == "CREATE_CALENDAR_EVENT":
        name = str(slots.get("contact_name") or "")
        plan.update(
            recipient_type="CONTACT_NAME" if name else "NONE",
            recipient_value=name,
            calendar_title=canonical_calendar_title(slots.get("title"), original_prompt),
            date_expression=slots.get("date_expression"),
            time_expression=slots.get("time_expression"),
        )
    elif stage_intent == "UPDATE_CONTACT":
        field = slots.get("update_field")
        plan.update(
            recipient_type="CONTACT_NAME",
            recipient_value=slots.get("contact_name", ""),
            updates={field: slots.get("update_value")} if field else {},
        )
    if stage_intent in {"COMPOSE_EMAIL", "COMPOSE_SMS"}:
        plan["content_goal"] = recipient_facing_goal(slots.get("content_goal", ""))
        plan["tone"] = slots.get("tone", "")
    return plan


def controller_args(args: argparse.Namespace) -> SimpleNamespace:
    return SimpleNamespace(
        backend=args.backend,
        max_num_tokens=None,
        top_k=20,
        top_p=0.95,
        temperature=0.2,
        seed=42,
        cpu_thread_count=None,
        cache="disk",
        timeout=args.timeout,
        dry_run=False,
        staged_python=str(Path(args.python).expanduser().absolute()),
    )


def staged_generate_content(
    cli: Path,
    model: Path,
    args: SimpleNamespace,
    case: dict[str, Any],
    channel: str,
    goal: str,
    recipient_name: str,
    recipient_company: str = "",
    recipient_title: str = "",
    requested_tone: str = "",
) -> tuple[dict[str, Any] | None, list[dict[str, Any]], list[str]]:
    del cli
    command = [
        args.staged_python, str(CONTENT_WORKER),
        "--model", str(model),
        "--channel", channel,
        "--original", str(case["prompt"]),
        "--goal", goal,
        "--recipient-name", recipient_name,
        "--recipient-company", recipient_company,
        "--recipient-title", recipient_title,
        "--tone", requested_tone,
        "--backend", args.backend,
        "--cache-dir", str(model.parent),
    ]
    run = invoke_worker(command, args.timeout)
    stdout = str(run.get("stdout") or "")
    payload = None
    for line in stdout.splitlines():
        if line.startswith("HJP_CONTENT_RESULT_JSON="):
            payload = json.loads(line.split("=", 1)[1])
    run["content_result"] = payload
    if not isinstance(payload, dict):
        return None, [run], ["content_worker_result_missing"]
    value = payload.get("value") if payload.get("success") else None
    errors = []
    if value is None:
        attempts = payload.get("attempts") or []
        errors = (attempts[-1].get("errors") if attempts else ["content_generation_failed"])
    return value, [run], [str(error) for error in errors]


def main() -> int:
    args = parse_args()
    model = resolve_path(args.model)
    python = Path(args.python).expanduser()
    if not python.is_absolute():
        python = (Path.cwd() / python).absolute()
    tests_file = resolve_path(args.tests_file)
    cli = resolve_cli(args.cli)
    for required in (model, python, tests_file, WORKER, CONTENT_WORKER, cli):
        if not required.is_file():
            print(f"error: required file missing: {required}", file=sys.stderr)
            return 2
    selected = select_cases(read_jsonl(tests_file), args)
    if args.repeat < 1:
        print("error: --repeat must be at least 1", file=sys.stderr)
        return 2
    cases = [case for case in selected for _ in range(args.repeat)]
    output = resolve_path(args.output, Path.cwd()) if args.output else (
        DEFAULT_RESULTS / "gemma4-staged-intent-e-64.jsonl"
    )
    commands = [
        {"test_id": case["id"], "command": worker_command(python, model, args, case["prompt"])}
        for case in cases
    ]
    if args.dry_run:
        for item in commands:
            print(json.dumps(item, ensure_ascii=False))
        return 0
    metadata = {
        "record_type": "run_metadata",
        "architecture": "staged_intent_workflow_orchestrator",
        "ablation": "E",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "model_name": args.model_name,
        "model_path": str(model),
        "model_sha256": sha256_file(model),
        "backend": args.backend,
        "runtime": cli_version(cli),
        "python": str(python),
        "constrained_decoding": not args.no_constrained,
        "stage_repair_limit": 0 if args.no_repair else 1,
        "test_count": len(cases),
        "repeat": args.repeat,
        "tests_file": str(tests_file),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    legacy_benchmark.generate_content = staged_generate_content
    with output.open("w", encoding="utf-8") as stream:
        stream.write(json.dumps(metadata, ensure_ascii=False) + "\n")
        for index, (case, item) in enumerate(zip(cases, commands), 1):
            worker_run = invoke_worker(item["command"], args.timeout)
            staged = worker_run.get("staged_result") or {}
            stage1 = staged.get("stage1")
            stage2 = staged.get("stage2")
            classification_c = attempt_value(stage1, final=False)
            slots_c = attempt_value(stage2, final=False)
            classification_d = attempt_value(stage1, final=True)
            slots_d = attempt_value(stage2, final=True)
            plan_c = plan_from(classification_c, slots_c, str(case["prompt"]))
            plan_d = plan_from(classification_d, slots_d, str(case["prompt"]))
            controller = None
            if plan_d is not None:
                controller = Controller(
                    cli, model, controller_args(args), case, plan_d
                )
                controller.run()
            record = result_record(
                case,
                plan_d,
                [worker_run],
                [] if plan_d is not None else ["staged_plan_unavailable"],
                controller,
            )
            record.update({
                "architecture": "staged_intent_workflow_orchestrator",
                "stage1": stage1,
                "stage2": stage2,
                "classification_initial": classification_c,
                "classification_final": classification_d,
                "slots_initial": slots_c,
                "slots_final": slots_d,
                "plan_c_no_repair": plan_c,
                "plan_d_repair": plan_d,
                "constrained_decoding": staged.get("constrained_decoding"),
                "worker_error": staged.get("worker_error"),
                "semantic_action_repair": staged.get("semantic_action_repair"),
            })
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
            stream.flush()
            sequence = record["execution_sequence"]
            print(
                f"[{index}/{len(cases)}] {case['id']}: "
                f"stage1={classification_d} sequence={sequence}",
                flush=True,
            )
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
