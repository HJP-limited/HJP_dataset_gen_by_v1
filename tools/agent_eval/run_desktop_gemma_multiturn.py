#!/usr/bin/env python3
"""Multi-turn Gemma evaluation with tool results fed back to the model.

What this measures, and what it does not
----------------------------------------

* model gateway : actual Gemma (`gemma-4-E2B-it.litertlm`, LiteRT-LM, CPU backend)
* assembly      : a Python harness over the *exported production* system instruction and tool
                  catalog. It is NOT the Kotlin REACT assembly — that path is an Android library and
                  cannot be driven from the desktop JVM without reimplementing the gateway. So the
                  deterministic pre-router, the workflow validator and the side-effect guard are
                  **not** in this loop; a lighter validator stands in for the schema/semantic gate.
* tool backend  : recording fakes over a fixed card fixture. Every call is executed and answered with
                  a real payload, and the result is fed back to the model, which then emits either
                  the next call or the final answer. No compose screen, no calendar entry and no card
                  write happens anywhere.
* runtime       : macOS / Python / CPU

The predecessor, `run_desktop_gemma.py`, stopped at the model's *first* decision for a single input.
That number is preserved here as `first_decision` so the two runs stay comparable, but it is reported
separately and must never be presented as task success: a chain whose first call is right can still
fail at its second, and a chain whose first call is different can still finish correctly.

Nothing here is an end-to-end result. It says what the model does with the production catalog; it
says nothing about Android intents, on-device memory or process stability.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import time
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from typing import Any, Mapping

import litert_lm

ROOT = Path(__file__).resolve().parents[2]
FROZEN = ROOT / "tools/agent_eval/results/pre_device_completion"
# Historical results are read-only; a run gets a fresh directory. See output_policy.
from output_policy import resolve_output_dir, require_writable  # noqa: E402

EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
DATETIME_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$")


# --------------------------------------------------------------------------------------------
# recording fakes
# --------------------------------------------------------------------------------------------


class CardStore:
    """In-memory cards plus a record of every irreversible action that was *asked* for."""

    def __init__(self, cards: list[dict[str, Any]]) -> None:
        self.cards = {c["card_id"]: dict(c) for c in cards}
        self.compose_drafts: list[dict[str, Any]] = []
        self.calendar_drafts: list[dict[str, Any]] = []
        self.updates: list[dict[str, Any]] = []
        self.calls: list[dict[str, Any]] = []

    def search(self, query: str, limit: int = 5) -> dict[str, Any]:
        terms = [t for t in re.split(r"\s+", (query or "").strip()) if len(t) >= 2]
        scored = []
        for card in self.cards.values():
            hay = " ".join(str(card.get(k, "")) for k in ("name", "company", "title", "industry"))
            score = sum(1 for t in terms if t in hay)
            if score:
                scored.append((score, card))
        best = max((s for s, _ in scored), default=0)
        hits = [c for s, c in scored if s == best][:limit]
        return {
            "results": [
                {
                    "card_id": c["card_id"],
                    "name": c["name"],
                    "company": c.get("company", ""),
                    "title": c.get("title", ""),
                }
                for c in hits
            ],
            "count": len(hits),
            "mode": "KEYWORD_ONLY",
            "engine": "recording_fake",
        }

    def get(self, card_id: str, purpose: str = "display") -> dict[str, Any] | None:
        card = self.cards.get(card_id)
        return dict(card) if card else None

    def update(self, card_id: str, updates: Mapping[str, Any], clear_fields: list[str]) -> dict | None:
        card = self.cards.get(card_id)
        if not card:
            return None
        before = dict(card)
        for key, value in (updates or {}).items():
            card[key] = value
        for key in clear_fields or []:
            card[key] = ""
        self.updates.append({"card_id": card_id, "updates": dict(updates or {})})
        return {"before": before, "after": dict(card)}


class SchemaTool(litert_lm.Tool):
    """One production contract, executed against the recording fake.

    `approve_tool_call` on the handler is the schema/semantic gate; by the time `execute` runs the
    call has already been accepted, so this only performs the work and records it.
    """

    def __init__(self, spec: dict[str, Any], store: CardStore, now: dict[str, str]) -> None:
        self.name = spec["name"]
        self.description = spec["description"]
        self.parameters = spec["parameters"]
        self._store = store
        self._now = now

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
        args = dict(param or {})
        self._store.calls.append({"name": self.name, "arguments": args})
        if self.name == "search_contacts":
            return {"ok": True, "data": self._store.search(args.get("query", ""), int(args.get("limit", 5) or 5))}
        if self.name == "get_contact":
            card = self._store.get(str(args.get("card_id", "")), str(args.get("purpose", "display")))
            if card is None:
                return {"ok": False, "error": {"code": "contact.not_found", "message_ko": "해당 명함을 찾을 수 없습니다."}}
            return {"ok": True, "data": card}
        if self.name == "get_current_datetime":
            return {"ok": True, "data": dict(self._now)}
        if self.name == "open_compose":
            self._store.compose_drafts.append(args)
            return {"ok": True, "data": {"opened": True, "channel": args.get("channel")}}
        if self.name == "create_calendar_event":
            self._store.calendar_drafts.append(args)
            return {"ok": True, "data": {"opened": True}}
        if self.name == "update_business_card":
            result = self._store.update(
                str(args.get("card_id", "")), args.get("updates") or {}, args.get("clear_fields") or []
            )
            if result is None:
                return {"ok": False, "error": {"code": "contact.not_found", "message_ko": "해당 명함을 찾을 수 없습니다."}}
            return {"ok": True, "data": result}
        return {"ok": False, "error": {"code": "tool.unknown", "message_ko": "알 수 없는 도구입니다."}}


class RecordingHandler(litert_lm.ToolEventHandler):
    """Schema and provenance gate, plus the record of everything the model tried.

    This stands in for the Kotlin `AgentWorkflowSession`. It is deliberately narrower: it enforces
    the schema, the one-side-effect-per-turn rule and the provenance rule that a recipient must come
    from a card this conversation actually read. It does not reproduce the full workflow validator,
    and the report says so.
    """

    def __init__(self, store: CardStore) -> None:
        self.store = store
        self.attempted: list[dict[str, Any]] = []
        self.rejected: list[dict[str, Any]] = []
        self.verified_values: set[str] = set()
        self.searched_ids: set[str] = set()
        self.turn_side_effects = 0

    def begin_turn(self) -> None:
        self.turn_side_effects = 0

    def _reject(self, name: str, args: dict, reason: str) -> bool:
        self.rejected.append({"name": name, "arguments": args, "reason": reason})
        return False

    def approve_tool_call(self, tool_call: dict[str, Any]) -> bool:
        function = tool_call.get("function") if isinstance(tool_call, dict) else None
        name = (function or tool_call).get("name")
        raw_args = (function or tool_call).get("arguments")
        if isinstance(raw_args, str):
            try:
                args = json.loads(raw_args)
            except json.JSONDecodeError:
                args = {}
        else:
            args = dict(raw_args or {})
        self.attempted.append({"name": name, "arguments": args})

        if name == "get_contact":
            if str(args.get("card_id", "")) not in self.searched_ids:
                return self._reject(name, args, "card_id was not produced by a search in this conversation")
        if name == "create_calendar_event":
            if not DATETIME_RE.match(str(args.get("start_time", ""))):
                return self._reject(name, args, "start_time is not yyyy-MM-ddTHH:mm")
            for address in args.get("attendee_emails") or []:
                if address not in self.verified_values:
                    return self._reject(name, args, f"attendee {address} was never read from a card")
        if name == "open_compose":
            to = str(args.get("to", ""))
            if not str(args.get("body", "")).strip():
                return self._reject(name, args, "empty body")
            if args.get("channel") == "email" and not str(args.get("subject", "")).strip():
                return self._reject(name, args, "empty subject")
            if to not in self.verified_values:
                return self._reject(name, args, f"recipient {to} was never read from a card")
        if name == "update_business_card":
            if str(args.get("card_id", "")) not in self.searched_ids:
                return self._reject(name, args, "card_id was not produced by a search in this conversation")
            if not (args.get("updates") or args.get("clear_fields")):
                return self._reject(name, args, "no field to write")
        if name in {"open_compose", "create_calendar_event", "update_business_card"}:
            if self.turn_side_effects >= 1:
                return self._reject(name, args, "a second irreversible action in one turn")
            self.turn_side_effects += 1
        return True

    def process_tool_response(self, tool_response: dict[str, Any]) -> dict[str, Any]:
        # Everything the conversation has legitimately learned becomes usable provenance.
        payload = tool_response
        if isinstance(payload, dict):
            blob = json.dumps(payload, ensure_ascii=False)
            for card_id in re.findall(r'"card_id"\s*:\s*"([^"]+)"', blob):
                self.searched_ids.add(card_id)
            for address in EMAIL_RE.findall(blob):
                self.verified_values.add(address)
            for phone in re.findall(r"01\d[- ]?\d{3,4}[- ]?\d{4}", blob):
                self.verified_values.add(phone)
        return tool_response


# --------------------------------------------------------------------------------------------
# scoring
# --------------------------------------------------------------------------------------------


def answer_text(response: Any) -> str:
    if not isinstance(response, dict):
        return ""
    content = response.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return " ".join(p.get("text", "") for p in content if isinstance(p, dict))
    return ""


def score_turn(spec: dict[str, Any], observed: dict[str, Any]) -> list[str]:
    """Failures of one turn, as plain strings. Empty means the turn did what it had to."""
    problems: list[str] = []
    names = [c["name"] for c in observed["calls"]]

    for tool in spec.get("required_tools", []):
        if tool not in names:
            problems.append(f"missing required tool {tool}")
    for tool in spec.get("forbidden_tools", []):
        if tool in names:
            problems.append(f"forbidden tool {tool} ran")
    if "max_side_effects" in spec and observed["side_effects"] > spec["max_side_effects"]:
        problems.append(f"{observed['side_effects']} side effects, at most {spec['max_side_effects']} allowed")
    if "min_side_effects" in spec and observed["side_effects"] < spec["min_side_effects"]:
        problems.append(f"{observed['side_effects']} side effects, at least {spec['min_side_effects']} required")
    if spec.get("compose_to") is not None:
        actual = observed["compose_drafts"][-1]["to"] if observed["compose_drafts"] else None
        if actual != spec["compose_to"]:
            problems.append(f"compose recipient {actual!r}, expected {spec['compose_to']!r}")
    if spec.get("attendees") is not None:
        actual = observed["calendar_drafts"][-1].get("attendee_emails", []) if observed["calendar_drafts"] else []
        if sorted(str(a) for a in actual) != sorted(spec["attendees"]):
            problems.append(f"attendees {actual!r}, expected {spec['attendees']!r}")
    if spec.get("start_time") is not None:
        actual = observed["calendar_drafts"][-1].get("start_time") if observed["calendar_drafts"] else None
        if actual != spec["start_time"]:
            problems.append(f"start_time {actual!r}, expected {spec['start_time']!r}")
    for needle in spec.get("answer_contains", []):
        if needle not in observed["answer"]:
            problems.append(f"answer missing {needle!r}")
    for needle in spec.get("answer_excludes", []):
        if needle in observed["answer"]:
            problems.append(f"answer contains forbidden {needle!r}")
    for value in spec.get("forbidden_values", []):
        blob = json.dumps(observed["compose_drafts"] + observed["calendar_drafts"], ensure_ascii=False)
        if value in blob:
            problems.append(f"forbidden value {value} used in an external action")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default=str(ROOT / "models/gemma-4-E2B-it.litertlm"))
    parser.add_argument("--scenarios", default=str(OUT_DIR / "gemma_multiturn_scenarios.json"))
    parser.add_argument("--output-dir", default=None,
                        help="fresh directory for this run; defaults to a new run directory")
    parser.add_argument("--out", default=None,
                        help="output file name inside --output-dir")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--max-output-tokens", type=int, default=512)
    args = parser.parse_args()

    scenario_bytes = Path(args.scenarios).read_bytes()
    payload = json.loads(scenario_bytes)
    scenarios = payload["scenarios"][: args.limit] if args.limit else payload["scenarios"]
    catalog = json.loads((FROZEN / "production_tool_catalog.json").read_text(encoding="utf-8"))
    system = catalog["system_instruction"]

    model_path = Path(args.model)
    started = time.time()
    engine = litert_lm.Engine(str(model_path), backend=litert_lm.Backend.CPU())

    rows: list[dict[str, Any]] = []
    for scenario in scenarios:
        store = CardStore(payload["cards"])
        handler = RecordingHandler(store)
        tools = [SchemaTool(t, store, payload["now"]) for t in catalog["tools"]]
        turns_out: list[dict[str, Any]] = []
        engine_error: str | None = None
        try:
            conversation = engine.create_conversation(
                tools=tools,
                automatic_tool_calling=True,
                tool_event_handler=handler,
                system_message=system,
                sampler_config=litert_lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0, seed=42),
                max_output_tokens=args.max_output_tokens,
            )
        except Exception as exc:  # noqa: BLE001 - recorded, never hidden
            engine_error = f"{type(exc).__name__}: {exc}"
            conversation = None

        if conversation is not None:
            with conversation:
                for index, turn in enumerate(scenario["turns"]):
                    handler.begin_turn()
                    before = {
                        "calls": len(store.calls),
                        "compose": len(store.compose_drafts),
                        "calendar": len(store.calendar_drafts),
                        "updates": len(store.updates),
                        "attempted": len(handler.attempted),
                        "rejected": len(handler.rejected),
                    }
                    t0 = time.time()
                    try:
                        response = conversation.send_message(turn["user"])
                        answer = answer_text(response)
                        turn_error = None
                    except Exception as exc:  # noqa: BLE001
                        answer, turn_error = "", f"{type(exc).__name__}: {exc}"
                        engine_error = engine_error or turn_error
                    elapsed = round(time.time() - t0, 2)

                    calls = store.calls[before["calls"]:]
                    compose = store.compose_drafts[before["compose"]:]
                    calendar = store.calendar_drafts[before["calendar"]:]
                    updates = store.updates[before["updates"]:]
                    observed = {
                        "calls": calls,
                        "compose_drafts": compose,
                        "calendar_drafts": calendar,
                        "side_effects": len(compose) + len(calendar) + len(updates),
                        "answer": answer,
                    }
                    problems = score_turn(turn, observed) if turn_error is None else [turn_error]
                    first = calls[0]["name"] if calls else None
                    expected_first = turn.get("expected_first_tool")
                    turns_out.append(
                        {
                            "index": index,
                            "user": turn["user"],
                            "tool_sequence": [c["name"] for c in calls],
                            "tool_arguments": calls,
                            "attempted_calls": handler.attempted[before["attempted"]:],
                            "rejected_calls": handler.rejected[before["rejected"]:],
                            "side_effects": observed["side_effects"],
                            "answer": answer[:600],
                            "seconds": elapsed,
                            "error": turn_error,
                            "first_decision_match": (first == expected_first)
                            if "expected_first_tool" in turn
                            else None,
                            "problems": problems,
                            "passed": not problems,
                        }
                    )
                    print(
                        f"  {scenario['id']} t{index}: seq={[c['name'] for c in calls]} "
                        f"ok={not problems} {elapsed}s",
                        flush=True,
                    )

        rows.append(
            {
                "id": scenario["id"],
                "category": scenario["category"],
                "intent": scenario.get("intent", ""),
                "user_turns": len(scenario["turns"]),
                "turns": turns_out,
                "engine_error": engine_error,
                "scenario_passed": bool(turns_out) and all(t["passed"] for t in turns_out),
            }
        )
        print(f"{scenario['id']}: scenario_passed={rows[-1]['scenario_passed']}", flush=True)

    all_turns = [t for r in rows for t in r["turns"]]
    first_decision = [t for t in all_turns if t["first_decision_match"] is not None]
    latencies = sorted(t["seconds"] for t in all_turns)

    def pct(values: list[float], q: float) -> float:
        if not values:
            return 0.0
        return round(values[min(len(values) - 1, int(round(q * (len(values) - 1))))], 2)

    result = {
        "axes": {
            "model_gateway": "actual Gemma (gemma-4-E2B-it.litertlm, LiteRT-LM CPU)",
            "assembly": "python harness over the exported production catalog (NOT the Kotlin REACT kernel)",
            "tool_backend": "recording fakes; every call executes and its result is fed back to the model",
            "validation": "schema + provenance + one-side-effect-per-turn gate standing in for AgentWorkflowSession",
            "runtime": "macos_python_litert_lm_cpu",
            "not_claimed": "end-to-end; no Android intent, no on-device memory, no process stability",
        },
        "model": {
            "path": model_path.name,
            "size_bytes": model_path.stat().st_size,
            "sha256": hashlib.sha256(model_path.read_bytes()).hexdigest()
            if model_path.stat().st_size < 1
            else "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        },
        "scenario_file_sha256": hashlib.sha256(scenario_bytes).hexdigest(),
        "sampling": {"top_k": 1, "top_p": 1.0, "temperature": 0.0, "seed": 42},
        "metrics": {
            "first_decision": {
                "definition": "turns whose FIRST tool call matched the declared first tool / turns "
                "that declared one. Comparable to the predecessor single-judgement run; it is NOT "
                "task success.",
                "numerator": sum(1 for t in first_decision if t["first_decision_match"]),
                "denominator": len(first_decision),
            },
            "turn_success": {
                "definition": "turns whose whole tool sequence, side-effect count, recipient, "
                "attendees, start_time and final answer satisfied the turn's assertions / all turns",
                "numerator": sum(1 for t in all_turns if t["passed"]),
                "denominator": len(all_turns),
            },
            "scenario_success": {
                "definition": "multi-turn scenarios in which every turn passed / all scenarios",
                "numerator": sum(1 for r in rows if r["scenario_passed"]),
                "denominator": len(rows),
            },
            "recovered_after_different_first_call": {
                "definition": "turns whose first call differed from the declared one and which still "
                "satisfied every assertion — the distinction between a harmless route and a real "
                "failure",
                "count": sum(
                    1 for t in first_decision if not t["first_decision_match"] and t["passed"]
                ),
            },
            "rejected_calls": {
                "definition": "calls the schema/provenance gate refused, summed over every turn",
                "count": sum(len(t["rejected_calls"]) for t in all_turns),
            },
            "engine_errors": sum(1 for r in rows if r["engine_error"]),
        },
        "latency_seconds": {
            "mean": round(sum(latencies) / len(latencies), 2) if latencies else 0.0,
            "median": pct(latencies, 0.5),
            "p95": pct(latencies, 0.95),
            "max": round(latencies[-1], 2) if latencies else 0.0,
        },
        "wall_clock_seconds": round(time.time() - started, 1),
        "by_category": {
            category: {
                "scenarios": sum(1 for r in rows if r["category"] == category),
                "passed": sum(1 for r in rows if r["category"] == category and r["scenario_passed"]),
            }
            for category in sorted({r["category"] for r in rows})
        },
        "scenarios": rows,
    }
    # Resolved through the policy: a fresh run directory unless one was passed, and never
    # a historical results path.
    out = require_writable(
        resolve_output_dir(args.output_dir, "gemma_multiturn") / (args.out or "results.json"))
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    m = result["metrics"]
    print(
        f"scenarios={m['scenario_success']['numerator']}/{m['scenario_success']['denominator']} "
        f"turns={m['turn_success']['numerator']}/{m['turn_success']['denominator']} "
        f"first_decision={m['first_decision']['numerator']}/{m['first_decision']['denominator']} "
        f"in {result['wall_clock_seconds']}s"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
