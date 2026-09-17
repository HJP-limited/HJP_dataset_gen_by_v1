import hashlib
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "tools/agent_eval_multiturn_v1/data"
E36 = DATA / "eval_set_v1_e36.json"
E37 = DATA / "eval_set_v1_e37.json"
MANIFEST = DATA / "eval_set_v1_e37_changes.json"

E36_SHA = "39c34fe152c9394fbf9fec7c4de89b3f4a2561ce5e1226356ae4938e90538cbe"
E37_SHA = "7e028767bf95cefc7438885ac572cb3db4aba94f485b41393d7f0702f07f4961"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class E37OfficialContractTest(unittest.TestCase):
    def test_canonical_hash_scope_and_manifest(self):
        source = E36.read_bytes()
        output = E37.read_bytes()
        contract = json.loads(output)
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

        self.assertEqual(sha256(source), E36_SHA)
        self.assertEqual(sha256(output), E37_SHA)
        self.assertEqual(len(contract["scenarios"]), 400)
        self.assertEqual(sum(s["turn_count"] for s in contract["scenarios"]), 1_918)
        ids = [s["scenario_id"] for s in contract["scenarios"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(manifest["input_sha256"], E36_SHA)
        self.assertEqual(manifest["output_sha256"], E37_SHA)
        self.assertEqual(manifest["scenario_count"], 400)
        self.assertEqual(manifest["turn_count"], 1_918)

    def test_generator_definition_is_byte_reproducible(self):
        data = json.loads(E36.read_text(encoding="utf-8"))
        contract = dict(data.get("contract", {}))
        contract.update({
            "name": "E-3.7",
            "revision": "Policy-A independent audit from reproducible E-3.6 source",
            "source_e36_sha256": E36_SHA,
            "policy_audit": {
                "scope": "400 scenarios / 1,918 turns",
                "search_two_plus": "clarification_without_implicit_promotion",
                "unique_exact_name": "direct_get_contact_allowed",
                "runtime_dependent_ambiguity": "not normalized without declared candidate cardinality",
                "static_contradictions_found": 0,
            },
        })
        data["contract"] = contract
        regenerated = (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        self.assertEqual(regenerated, E37.read_bytes())

    def test_primary_expectations_do_not_conflict_with_turn_forbidden_tools(self):
        data = json.loads(E37.read_text(encoding="utf-8"))
        conflicts = []
        for scenario in data["scenarios"]:
            for turn in scenario["turns"]:
                forbidden = set(turn.get("forbidden_tools", []))
                primary = set(turn.get("primary_sequence", []))
                expected = {call["tool"] for call in turn.get("expected_calls", [])}
                overlap = forbidden & (primary | expected)
                if overlap:
                    conflicts.append((scenario["scenario_id"], turn["index"], sorted(overlap)))
        self.assertEqual(conflicts, [])

    def test_policy_a_direct_read_alternatives_are_narrow_and_declared(self):
        """Bound the legacy representation where Policy A alternatives overlap forbidden_tools.

        `forbidden_tools` describes the primary search-first route in these turns, while
        `alternative_expected_calls` and `allowed_tool_sequences` explicitly permit a unique-name
        direct read. The scorer consumes the declared alternative. No other tool or shape may use
        this compatibility representation without an intentional contract revision.
        """
        data = json.loads(E37.read_text(encoding="utf-8"))
        overlaps = []
        for scenario in data["scenarios"]:
            for turn in scenario["turns"]:
                forbidden = set(turn.get("forbidden_tools", []))
                alternatives = {call["tool"] for call in turn.get("alternative_expected_calls", [])}
                overlap = forbidden & alternatives
                if overlap:
                    overlaps.append((scenario["scenario_id"], turn["index"], overlap))

        self.assertEqual(len(overlaps), 15)
        self.assertTrue(all(turn == 1 and tools == {"get_contact"} for _, turn, tools in overlaps))


if __name__ == "__main__":
    unittest.main()
