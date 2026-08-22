"""Export Ryeong's multiturn scenarios to a neutral, byte-deterministic JSON manifest.

This does not reimplement `build_scenarios()`. It imports the upstream file and calls the
upstream function, so the scenarios are the upstream scenarios and nothing else. The only
thing added is a positional index; no synthetic IDs, no reordering, no normalisation of the
Korean text.

Refuses to run unless both inputs hash to the values the freeze declares.

    PYTHONDONTWRITEBYTECODE=1 python3 tools/ryeong_multiturn_v4/export_scenarios.py --out <path>
"""

import argparse
import hashlib
import importlib.util
import json
import os
import random
import sys

# Upstream builds some `must` alternative lists out of sets (address_variants,
# company_variants). Set iteration order for strings follows PYTHONHASHSEED, which CPython
# randomises per process, so two runs of the untouched upstream code emit the same
# alternatives in different orders. Scoring is unaffected — check_answer() uses any() — but a
# frozen manifest has to be byte-stable, so the seed is pinned here. It has to be set before
# the interpreter starts, hence the re-exec. Nothing upstream is modified.
if os.environ.get("PYTHONHASHSEED") != "0":
    os.environ["PYTHONHASHSEED"] = "0"
    os.execv(sys.executable, [sys.executable] + sys.argv)

HERE = os.path.dirname(os.path.abspath(__file__))
REFERENCE = os.path.join(HERE, "reference")

EVALUATOR = os.path.join(REFERENCE, "eval_multiturn.py")
CARDS = os.path.join(REFERENCE, "cards_eval1000.json")

EVALUATOR_SHA = "26a522235fbbd73760229260e3cc4373ca6d66ce0a4d4ca9dbf612cd21d19031"
CARDS_SHA = "f0feaebfdf5eb26c2a161a4b8c40d1307a6f5fa9c68f00309f05b69d03e7cd24"
UPSTREAM_COMMIT = "1caec3a23d0c1ee8f6a8d4a5e54160dbb2dc81bc"
UPSTREAM_BRANCH = "llm-integration-work"
UPSTREAM_REMOTE = "https://github.com/HJP-limited/ryeong.git"

# The exact inventory the freeze declares. Export fails rather than emit anything else.
EXPECTED = {
    "scenarios": 130,
    "turns": 377,
    "kinds": 21,
    "known_gap": 0,
    "generate_only": 6,
    "depth_1": 3,
    "depth_2": 62,
    "depth_3_5": 55,
    "depth_6_10": 10,
    "depth_11_plus": 0,
}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_upstream():
    """Import the upstream evaluator without running it (it is __main__-guarded)."""
    spec = importlib.util.spec_from_file_location("ryeong_eval_multiturn", EVALUATOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def depth_bucket(n):
    if n == 1:
        return "depth_1"
    if n == 2:
        return "depth_2"
    if n <= 5:
        return "depth_3_5"
    if n <= 10:
        return "depth_6_10"
    return "depth_11_plus"


def export(module, cards):
    """Call the upstream builder and flatten it, preserving order and every field."""
    scenarios = module.build_scenarios(cards, random.Random(module.SEED))
    out = []
    for index, scenario in enumerate(scenarios):
        turns = []
        for depth, turn in enumerate(scenario["turns"], start=1):
            turns.append({
                "depth": depth,
                "question": turn["q"],
                "expected_route": turn["route"],
                "expected_slots": turn["slots"],
                "gold_card_ids": list(turn["gold"] or []),
                "must": [list(alternatives) for alternatives in (turn["must"] or [])],
                "must_not": list(turn["must_not"] or []),
                "no_cards": bool(turn.get("no_cards")),
            })
        out.append({
            "index": index,
            "kind": scenario["kind"],
            "known_gap": bool(scenario.get("known_gap")),
            "generate_only": bool(scenario.get("generate_only")),
            "turn_count": len(turns),
            "turns": turns,
        })
    return out


def inventory(scenarios):
    kinds = {}
    depths = {k: 0 for k in ("depth_1", "depth_2", "depth_3_5", "depth_6_10", "depth_11_plus")}
    turns = 0
    known_gap = 0
    generate_only = 0
    for scenario in scenarios:
        kinds[scenario["kind"]] = kinds.get(scenario["kind"], 0) + 1
        depths[depth_bucket(scenario["turn_count"])] += 1
        turns += scenario["turn_count"]
        known_gap += 1 if scenario["known_gap"] else 0
        generate_only += 1 if scenario["generate_only"] else 0
    return {
        "scenarios": len(scenarios),
        "turns": turns,
        "kinds": len(kinds),
        "known_gap": known_gap,
        "generate_only": generate_only,
        "kind_counts": dict(sorted(kinds.items())),
        **depths,
    }


def gate(inv, scenarios, card_ids):
    """Every check is exact. A near miss is a failure, not a warning."""
    failures = []
    for key, want in EXPECTED.items():
        got = inv.get(key)
        if got != want:
            failures.append("%s: expected %s, got %s" % (key, want, got))

    seen = set()
    for scenario in scenarios:
        if scenario["index"] in seen:
            failures.append("duplicate scenario index %s" % scenario["index"])
        seen.add(scenario["index"])

    referenced = {cid for s in scenarios for t in s["turns"] for cid in t["gold_card_ids"]}
    missing = sorted(referenced - card_ids)
    if missing:
        failures.append("gold card IDs absent from the card set: %s" % missing[:10])

    return failures


def serialise(payload):
    """One canonical byte sequence: sorted keys, fixed separators, UTF-8, trailing newline."""
    return json.dumps(payload, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), indent=2) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, help="path of the scenario JSON to write")
    parser.add_argument("--stdout-only", action="store_true",
                        help="compute and report without writing (used by the determinism check)")
    args = parser.parse_args()

    evaluator_sha = sha256(EVALUATOR)
    cards_sha = sha256(CARDS)
    if evaluator_sha != EVALUATOR_SHA:
        print("[gate] eval_multiturn.py SHA mismatch\n  expected %s\n  actual   %s"
              % (EVALUATOR_SHA, evaluator_sha))
        return 2
    if cards_sha != CARDS_SHA:
        print("[gate] cards_eval1000.json SHA mismatch\n  expected %s\n  actual   %s"
              % (CARDS_SHA, cards_sha))
        return 2
    print("[input] eval_multiturn.py  %s  OK" % evaluator_sha)
    print("[input] cards_eval1000.json %s  OK" % cards_sha)

    module = load_upstream()
    if module.SEED != 42:
        print("[gate] upstream SEED is %s, expected 42" % module.SEED)
        return 2

    with open(CARDS, encoding="utf-8") as handle:
        cards = json.load(handle)
    card_ids = {c["id"] for c in cards}
    if len(card_ids) != len(cards):
        print("[gate] duplicate card IDs in cards_eval1000.json")
        return 2
    print("[input] cards: %d, duplicate ids: 0" % len(cards))

    scenarios = export(module, cards)
    inv = inventory(scenarios)
    failures = gate(inv, scenarios, card_ids)
    print("[export] scenarios=%(scenarios)d turns=%(turns)d kinds=%(kinds)d "
          "known_gap=%(known_gap)d generate_only=%(generate_only)d" % inv)
    print("[export] depth 1=%(depth_1)d 2=%(depth_2)d 3-5=%(depth_3_5)d "
          "6-10=%(depth_6_10)d 11+=%(depth_11_plus)d" % inv)
    if failures:
        for failure in failures:
            print("[gate] FAIL %s" % failure)
        return 3
    print("[gate] all exact gates passed")

    payload = {
        "schema": "ryeong_multiturn_scenarios/v1",
        "provenance": {
            "remote": UPSTREAM_REMOTE,
            "branch": UPSTREAM_BRANCH,
            "commit": UPSTREAM_COMMIT,
            "evaluator": "scripts/eval_multiturn.py",
            "evaluator_sha256": evaluator_sha,
            "cards": "data/cards_eval1000.json",
            "cards_sha256": cards_sha,
            "seed": module.SEED,
            "builder": "upstream build_scenarios(cards, random.Random(42)) — called, not reimplemented",
            "server_contacted": False,
            "model_contacted": False,
        },
        "inventory": inv,
        "scenarios": scenarios,
    }
    text = serialise(payload)
    body_sha = hashlib.sha256(text.encode("utf-8")).hexdigest()
    print("[export] payload sha256 %s (%d bytes)" % (body_sha, len(text.encode("utf-8"))))

    if args.stdout_only:
        return 0

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    print("[export] wrote %s" % args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
