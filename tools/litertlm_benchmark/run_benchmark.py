#!/usr/bin/env python3
"""Run isolated LiteRT-LM CLI requests and preserve their complete output."""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


BENCHMARK_DIR = Path(__file__).resolve().parent
REPO_ROOT = BENCHMARK_DIR.parents[1]
DEFAULT_TESTS = BENCHMARK_DIR / "test_cases.jsonl"
DEFAULT_PRESET = BENCHMARK_DIR / "hjp_tools_preset.py"
DEFAULT_MODELS = BENCHMARK_DIR / "models.json"
DEFAULT_RESULTS = BENCHMARK_DIR / "results"
ANSI_RE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
EVENT_RE = re.compile(r"\[(tool_call|tool_response)\]\s*(.*)$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run HJP tool-calling cases through the installed litert-lm CLI. "
            "Each case starts a separate process."
        )
    )
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--model", help="Path to one local .litertlm model")
    source.add_argument(
        "--models-config",
        type=Path,
        help="JSON config (default: tools/litertlm_benchmark/models.json)",
    )
    parser.add_argument("--model-name", help="Label for --model")
    parser.add_argument(
        "--backend",
        choices=("cpu", "gpu"),
        help="Override configured backend",
    )
    parser.add_argument(
        "--tests-file",
        type=Path,
        default=DEFAULT_TESTS,
        help="JSONL test case file",
    )
    parser.add_argument(
        "--preset",
        type=Path,
        default=DEFAULT_PRESET,
        help="LiteRT-LM Python preset",
    )
    parser.add_argument(
        "--cli",
        help="litert-lm executable (default: local .venv, then PATH)",
    )
    parser.add_argument(
        "--test-case",
        action="append",
        default=[],
        help="Run only this test ID; repeatable",
    )
    parser.add_argument(
        "--category",
        action="append",
        default=[],
        help="Run only this category; repeatable",
    )
    parser.add_argument("--limit", type=int, help="Limit selected cases")
    parser.add_argument(
        "--timeout",
        type=float,
        default=180.0,
        help="Per-case timeout in seconds (default: 180)",
    )
    parser.add_argument(
        "--output",
        type=Path,
        help="Output JSONL path; valid only for one model",
    )
    parser.add_argument("--max-num-tokens", type=int)
    parser.add_argument("--top-k", type=int)
    parser.add_argument("--top-p", type=float)
    parser.add_argument("--temperature", type=float)
    parser.add_argument("--seed", type=int)
    parser.add_argument("--cpu-thread-count", type=int)
    parser.add_argument("--cache", choices=("disk", "memory", "no"))
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print commands without starting model processes",
    )
    return parser.parse_args()


def resolve_path(path: str | Path, base: Path = REPO_ROOT) -> Path:
    candidate = Path(path).expanduser()
    if not candidate.is_absolute():
        candidate = base / candidate
    return candidate.resolve()


def resolve_cli(value: str | None) -> Path:
    if value:
        found = shutil.which(value)
        return Path(found if found else value).expanduser().resolve()
    environment_cli = os.environ.get("LITERT_LM_CLI")
    if environment_cli:
        return resolve_cli(environment_cli)
    local_cli = BENCHMARK_DIR / ".venv" / "bin" / "litert-lm"
    if local_cli.is_file():
        return local_cli.resolve()
    found = shutil.which("litert-lm")
    if found:
        return Path(found).resolve()
    return local_cli.resolve()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_number}: {exc}") from exc
            if not isinstance(value, dict) or not isinstance(
                value.get("id"), str
            ):
                raise ValueError(
                    f"{path}:{line_number}: test case needs a string id"
                )
            cases.append(value)
    ids = [case["id"] for case in cases]
    if len(ids) != len(set(ids)):
        raise ValueError(f"{path}: duplicate test case id")
    return cases


def select_cases(
    cases: list[dict[str, Any]], args: argparse.Namespace
) -> list[dict[str, Any]]:
    selected = cases
    if args.test_case:
        requested = set(args.test_case)
        selected = [case for case in selected if case["id"] in requested]
        missing = requested - {case["id"] for case in selected}
        if missing:
            raise ValueError(f"Unknown test IDs: {', '.join(sorted(missing))}")
    if args.category:
        categories = set(args.category)
        selected = [
            case for case in selected if case.get("category") in categories
        ]
    if args.limit is not None:
        if args.limit < 1:
            raise ValueError("--limit must be at least 1")
        selected = selected[: args.limit]
    if not selected:
        raise ValueError("No test cases selected")
    return selected


def load_models(args: argparse.Namespace) -> list[dict[str, Any]]:
    if args.model:
        return [
            {
                "name": args.model_name
                or Path(args.model).stem
                or "unnamed-model",
                "path": str(resolve_path(args.model)),
                "backend": args.backend or "cpu",
            }
        ]

    config_path = (
        resolve_path(args.models_config)
        if args.models_config
        else DEFAULT_MODELS.resolve()
    )
    if not config_path.is_file():
        raise ValueError(
            f"Model config not found: {config_path}. "
            "Use --model or copy models.example.json to models.json."
        )
    with config_path.open("r", encoding="utf-8") as stream:
        config = json.load(stream)
    entries = config.get("models") if isinstance(config, dict) else config
    if not isinstance(entries, list) or not entries:
        raise ValueError(f"{config_path}: models must be a non-empty list")
    models: list[dict[str, Any]] = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict) or not entry.get("path"):
            raise ValueError(
                f"{config_path}: models[{index}] needs name and path"
            )
        backend = args.backend or entry.get("backend", "cpu")
        if backend not in ("cpu", "gpu"):
            raise ValueError(
                f"{config_path}: models[{index}] backend must be cpu or gpu"
            )
        models.append(
            {
                "name": str(entry.get("name") or Path(entry["path"]).stem),
                "path": str(resolve_path(entry["path"])),
                "backend": backend,
            }
        )
    return models


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def command_for(
    cli: Path,
    model: Path,
    backend: str,
    prompt: str,
    preset: Path,
    args: argparse.Namespace,
) -> list[str]:
    command = [
        str(cli),
        "run",
        str(model),
        "--prompt",
        prompt,
        "--preset",
        str(preset),
        "--backend",
        backend,
    ]
    optional = (
        ("--max-num-tokens", args.max_num_tokens),
        ("--top-k", args.top_k),
        ("--top-p", args.top_p),
        ("--temperature", args.temperature),
        ("--seed", args.seed),
        ("--cpu-thread-count", args.cpu_thread_count),
        ("--cache", args.cache),
    )
    for flag, value in optional:
        if value is not None:
            command.extend((flag, str(value)))
    return command


def decode_output(data: bytes) -> str:
    return data.decode("utf-8", errors="replace")


def parse_events(stdout: str) -> tuple[
    list[dict[str, Any]], list[Any], list[dict[str, Any]], str
]:
    clean = ANSI_RE.sub("", stdout).replace("\b", "")
    tool_calls: list[dict[str, Any]] = []
    tool_responses: list[Any] = []
    errors: list[dict[str, Any]] = []
    assistant_lines: list[str] = []

    for line_number, line in enumerate(clean.splitlines(), 1):
        event = EVENT_RE.search(line)
        if event:
            prefix = line[: event.start()].strip()
            if prefix:
                assistant_lines.append(prefix)
            kind, payload = event.groups()
            try:
                parsed = json.loads(payload)
            except json.JSONDecodeError as exc:
                errors.append(
                    {
                        "line": line_number,
                        "event": kind,
                        "payload": payload,
                        "error": str(exc),
                    }
                )
                continue
            if kind == "tool_response":
                tool_responses.append(parsed)
                continue
            if not isinstance(parsed, dict):
                errors.append(
                    {
                        "line": line_number,
                        "event": kind,
                        "payload": payload,
                        "error": "tool_call payload is not an object",
                    }
                )
                continue
            arguments = parsed.get("arguments")
            arguments_error = None
            if isinstance(arguments, str):
                try:
                    arguments = json.loads(arguments)
                except json.JSONDecodeError as exc:
                    arguments_error = str(exc)
            if arguments is None:
                arguments = {}
            normalized = {
                "name": parsed.get("name"),
                "arguments": arguments,
                "raw_event": parsed,
            }
            if arguments_error:
                normalized["arguments_parse_error"] = arguments_error
                errors.append(
                    {
                        "line": line_number,
                        "event": kind,
                        "payload": payload,
                        "error": f"arguments JSON: {arguments_error}",
                    }
                )
            tool_calls.append(normalized)
            continue

        stripped = line.strip()
        if (
            not stripped
            or stripped.startswith("Loading preset from ")
            or stripped.startswith("- System instruction:")
            or stripped == "- Tools:"
            or stripped.startswith("- Extra context:")
            or (
                stripped.startswith("- ")
                and stripped[2:]
                in {
                    "search_contacts",
                    "get_contact",
                    "update_business_card",
                    "create_calendar_event",
                    "open_compose",
                    "compose_email",
                    "compose_sms",
                    "get_current_datetime",
                }
            )
        ):
            continue
        assistant_lines.append(line)

    return tool_calls, tool_responses, errors, "\n".join(
        assistant_lines
    ).strip()


def cli_version(cli: Path) -> dict[str, Any]:
    try:
        completed = subprocess.run(
            [str(cli), "--version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=20,
            check=False,
        )
        return {
            "command": [str(cli), "--version"],
            "return_code": completed.returncode,
            "stdout": decode_output(completed.stdout).strip(),
            "stderr": decode_output(completed.stderr).strip(),
        }
    except Exception as exc:  # preserve inspection failure in metadata
        return {
            "command": [str(cli), "--version"],
            "return_code": None,
            "stdout": "",
            "stderr": repr(exc),
        }


def run_case(
    command: list[str],
    timeout: float,
    case: dict[str, Any],
    environment_overrides: dict[str, str] | None = None,
) -> dict[str, Any]:
    started = dt.datetime.now(dt.timezone.utc).isoformat()
    before = time.monotonic()
    timed_out = False
    launch_error = None
    return_code: int | None = None
    stdout_bytes = b""
    stderr_bytes = b""
    try:
        process_environment = os.environ.copy()
        process_environment["HJP_BENCHMARK_TEST_ID"] = str(case["id"])
        process_environment["HJP_BENCHMARK_PROMPT"] = str(
            case.get("prompt") or ""
        )
        if environment_overrides:
            process_environment.update(environment_overrides)
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=process_environment,
        )
        try:
            stdout_bytes, stderr_bytes = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            process.kill()
            stdout_bytes, stderr_bytes = process.communicate()
        return_code = process.returncode
    except Exception as exc:
        launch_error = repr(exc)

    duration = time.monotonic() - before
    stdout = decode_output(stdout_bytes)
    stderr = decode_output(stderr_bytes)
    runtime_error_detected = bool(
        "An error occurred" in stdout
        or "Traceback (most recent call last):" in stderr
        or re.search(
            r"(?m)^(RuntimeError|ValueError|TypeError|"
            r"MemoryError|OSError):",
            stderr,
        )
    )
    tool_calls, tool_responses, parse_errors, assistant_output = parse_events(
        stdout
    )
    return {
        "record_type": "test_result",
        "test_id": case["id"],
        "category": case.get("category"),
        "prompt": case.get("prompt"),
        "test_case": case,
        "started_at_utc": started,
        "duration_seconds": round(duration, 6),
        "timeout_seconds": timeout,
        "timed_out": timed_out,
        "launch_error": launch_error,
        "runtime_error_detected": runtime_error_detected,
        "command": command,
        "command_shell": shlex.join(command),
        "return_code": return_code,
        "stdout": stdout,
        "stderr": stderr,
        "stdout_base64": base64.b64encode(stdout_bytes).decode("ascii"),
        "stderr_base64": base64.b64encode(stderr_bytes).decode("ascii"),
        "tool_calls": tool_calls,
        "tool_responses": tool_responses,
        "event_parse_errors": parse_errors,
        "assistant_output": assistant_output,
    }


def safe_slug(value: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-._")
    return slug or "model"


def output_path(
    model_name: str,
    backend: str,
    explicit: Path | None,
    multi_model: bool,
) -> Path:
    if explicit:
        if multi_model:
            raise ValueError("--output cannot be used with multiple models")
        return resolve_path(explicit, Path.cwd())
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return DEFAULT_RESULTS / (
        f"{safe_slug(model_name)}_{backend}_{timestamp}.jsonl"
    )


def validate_environment(
    cli: Path,
    preset: Path,
    tests_file: Path,
    models: list[dict[str, Any]],
) -> None:
    missing = []
    for label, path in (
        ("CLI", cli),
        ("preset", preset),
        ("tests", tests_file),
    ):
        if not path.is_file():
            missing.append(f"{label}: {path}")
    for model in models:
        path = Path(model["path"])
        if not path.is_file():
            missing.append(f"model {model['name']}: {path}")
    if missing:
        raise ValueError("Missing required file(s):\n  " + "\n  ".join(missing))


def main() -> int:
    args = parse_args()
    try:
        tests_file = resolve_path(args.tests_file, Path.cwd())
        preset = resolve_path(args.preset, Path.cwd())
        cli = resolve_cli(args.cli)
        cases = select_cases(read_jsonl(tests_file), args)
        models = load_models(args)
        validate_environment(cli, preset, tests_file, models)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    if args.dry_run:
        for model in models:
            for case in cases:
                command = command_for(
                    cli,
                    Path(model["path"]),
                    model["backend"],
                    case["prompt"],
                    preset,
                    args,
                )
                print(
                    json.dumps(
                        {
                            "model": model["name"],
                            "test_id": case["id"],
                            "command": command,
                            "command_shell": shlex.join(command),
                        },
                        ensure_ascii=False,
                    )
                )
        return 0

    version = cli_version(cli)
    overall_failure = False
    for model in models:
        model_path = Path(model["path"])
        try:
            model_hash = sha256_file(model_path)
            preset_hash = sha256_file(preset)
            tests_hash = sha256_file(tests_file)
            destination = output_path(
                model["name"],
                model["backend"],
                args.output,
                len(models) > 1,
            )
            destination.parent.mkdir(parents=True, exist_ok=True)
        except (OSError, ValueError) as exc:
            print(f"error: {exc}", file=sys.stderr)
            overall_failure = True
            continue

        metadata = {
            "record_type": "run_metadata",
            "format_version": 1,
            "run_started_at_utc": dt.datetime.now(
                dt.timezone.utc
            ).isoformat(),
            "model_name": model["name"],
            "model_path": str(model_path),
            "model_size_bytes": model_path.stat().st_size,
            "model_sha256": model_hash,
            "cli": version,
            "backend": model["backend"],
            "python_version": sys.version,
            "preset_path": str(preset),
            "preset_sha256": preset_hash,
            "tests_path": str(tests_file),
            "tests_sha256": tests_hash,
            "selected_test_ids": [case["id"] for case in cases],
            "cli_run_options": {
                "max_num_tokens": args.max_num_tokens,
                "top_k": args.top_k,
                "top_p": args.top_p,
                "temperature": args.temperature,
                "seed": args.seed,
                "cpu_thread_count": args.cpu_thread_count,
                "cache": args.cache,
                "timeout_seconds": args.timeout,
            },
        }
        with destination.open("w", encoding="utf-8") as stream:
            stream.write(json.dumps(metadata, ensure_ascii=False) + "\n")
            for index, case in enumerate(cases, 1):
                print(
                    f"[{model['name']}] {index}/{len(cases)} "
                    f"{case['id']}",
                    flush=True,
                )
                command = command_for(
                    cli,
                    model_path,
                    model["backend"],
                    case["prompt"],
                    preset,
                    args,
                )
                result = run_case(command, args.timeout, case)
                result["model_name"] = model["name"]
                result["model_sha256"] = model_hash
                result["backend"] = model["backend"]
                result["cli_version"] = version
                stream.write(json.dumps(result, ensure_ascii=False) + "\n")
                stream.flush()
        print(f"RESULT_FILE={destination.resolve()}")

    return 1 if overall_failure else 0


if __name__ == "__main__":
    raise SystemExit(main())
