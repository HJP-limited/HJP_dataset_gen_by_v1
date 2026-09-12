#!/usr/bin/env python3
"""Build E-3.6 by correcting only Policy-A update-contract bookkeeping."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e35.json"
DST = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e36.json"
LOG = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e36_changes.json"

SEARCH_GROUP = {
    "DEV-0009", "DEV-0026", "DEV-0027", "DEV-0033", "DEV-0044",
    "DEV-0069", "DEV-0070", "DEV-0077", "DEV-0079", "DEV-0090",
    "DEV-0109", "REG-0006", "REG-0012", "REG-0020", "REG-0039",
    "REG-0044", "REG-0070", "HLD-0037", "HLD-0039", "HLD-0040",
    "HLD-0052", "HLD-0082", "HLD-0086", "HLD-0089", "HLD-0098",
    "HLD-0129", "HLD-0145", "HLD-0157", "HLD-0180", "HLD-0190",
    "DEV-0049", "DEV-0050", "DEV-0058", "REG-0008", "REG-0013",
    "REG-0024", "REG-0041", "REG-0068", "HLD-0005", "HLD-0107",
    "HLD-0152", "HLD-0181", "HLD-0200",
}
FINAL_TARGET_GROUP = {"DEV-0049": 7, "DEV-0050": 4, "DEV-0058": 5,
    "REG-0008": 5, "REG-0013": 4, "REG-0024": 7, "REG-0041": 4,
    "REG-0068": 6, "HLD-0005": 4, "HLD-0107": 5, "HLD-0152": 4,
    "HLD-0181": 5, "HLD-0200": 5}

d = json.loads(SRC.read_text(encoding="utf-8"))
changes = []
for s in d["scenarios"]:
    sid = s["scenario_id"]
    turns = s.get("turns", [])
    if sid in SEARCH_GROUP and "search_contacts" in s["success"].get("must_call", []):
        s["success"]["must_call"] = [x for x in s["success"]["must_call"] if x != "search_contacts"]
        changes.append({"scenario_id": sid, "field": "success.must_call", "from": "search_contacts", "to": "optional_under_direct_resolution"})
    # An explicit post-update named acquisition is represented by an
    # unreferenced later search turn.  Use the latest such declared turn.
    if sid in FINAL_TARGET_GROUP and "final_target_card_id" in s["success"]:
        old = s["success"].pop("final_target_card_id", None)
        turn = FINAL_TARGET_GROUP[sid]
        s["success"]["final_target_policy"] = {
            "kind": "explicit_new_target_after_turn",
            "turn": turn,
            "must_differ_from_target_turn": 3,
        }
        changes.append({"scenario_id": sid, "field": "success.final_target_card_id", "from": old, "to": s["success"]["final_target_policy"]})

d["contract"] = dict(d.get("contract", {}))
d["contract"]["name"] = "E-3.6"
d["contract"]["revision"] = "Policy-A update direct-resolution and explicit-target bookkeeping cleanup"
d["contract"]["update_contract_audit"] = {
    "unique_exact_name_direct_resolution": "scenario-global search is not mandatory when direct get_contact alternative is declared",
    "explicit_new_target": "latest explicit target transition supersedes update target for final-state evaluation",
}
DST.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
LOG.write_text(json.dumps(changes, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(DST)
print(LOG)
print("changes", len(changes))
