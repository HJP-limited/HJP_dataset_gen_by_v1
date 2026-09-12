#!/usr/bin/env python3
"""Create the reproducible E-3.7 contract from the frozen E-3.6 bytes.

This revision records the independent Policy-A audit.  No scenario or turn
expectation is changed unless a runtime-independent contradiction is proven.
The audit found none, so E-3.7 preserves the E-3.6 scenario payload byte-for-byte
inside a new, explicitly versioned contract envelope.
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e36.json"
DST = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e37.json"
MANIFEST = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e37_changes.json"
EXPECTED_SOURCE_SHA = "39c34fe152c9394fbf9fec7c4de89b3f4a2561ce5e1226356ae4938e90538cbe"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


source_bytes = SRC.read_bytes()
source_sha = sha256(source_bytes)
if source_sha != EXPECTED_SOURCE_SHA:
    raise SystemExit(f"unexpected E-3.6 source SHA: {source_sha}")

data = json.loads(source_bytes.decode("utf-8"))
if len(data["scenarios"]) != 400 or sum(s["turn_count"] for s in data["scenarios"]) != 1918:
    raise SystemExit("E-3.6 scope is not 400 scenarios / 1,918 turns")

contract = dict(data.get("contract", {}))
contract.update(
    {
        "name": "E-3.7",
        "revision": "Policy-A independent audit from reproducible E-3.6 source",
        "source_e36_sha256": source_sha,
        "policy_audit": {
            "scope": "400 scenarios / 1,918 turns",
            "search_two_plus": "clarification_without_implicit_promotion",
            "unique_exact_name": "direct_get_contact_allowed",
            "runtime_dependent_ambiguity": "not normalized without declared candidate cardinality",
            "static_contradictions_found": 0,
        },
    }
)
data["contract"] = contract

# E-3.7 deliberately makes no scenario/turn semantic changes.
output = (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
DST.write_bytes(output)
manifest = {
    "schema": "hjp_eval_contract_change_manifest/v1",
    "contract": "E-3.7",
    "input": str(SRC.relative_to(ROOT)),
    "input_sha256": source_sha,
    "output": str(DST.relative_to(ROOT)),
    "output_sha256": sha256(output),
    "scenario_count": len(data["scenarios"]),
    "turn_count": sum(s["turn_count"] for s in data["scenarios"]),
    "changed_scenario_count": 0,
    "changed_field_count": 0,
    "audit_findings": {
        "runtime_independent_contract_errors": 0,
        "runtime_conditioned_ambiguity_candidates": ["DEV-0010:T5", "REG-0014:T4"],
        "runtime_conditioned_candidates_changed": False,
    },
    "generation": "normalize_e37_policy_contract.py",
}
MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(DST)
print(MANIFEST)
print(manifest["output_sha256"])
