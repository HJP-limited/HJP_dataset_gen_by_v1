import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import date
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
import score
from score import field_ok, resolve_relative


class TemporalAnchorTest(unittest.TestCase):
    def test_relative_uses_scenario_anchor(self):
        self.assertEqual(resolve_relative("tomorrow_10:00", date(2026, 9, 9)), "2026-09-10T10:00")
        self.assertEqual(resolve_relative("tomorrow_10:00", date(2026, 12, 31)), "2027-01-01T10:00")

    def test_no_anchor_never_guesses(self):
        self.assertIsNone(resolve_relative("tomorrow_10:00", None))
        spec = {"cmp": "relative_datetime", "spec": "tomorrow_10:00"}
        self.assertFalse(field_ok(spec, True, "2026-09-10T10:00", temporal_anchor=None)[1])

    def test_absolute_unaffected(self):
        spec = {"cmp": "canonical_datetime", "value": "2026-09-10T10:00"}
        self.assertTrue(field_ok(spec, True, "2026-09-10T10:00", temporal_anchor=None)[1])

    def test_main_keeps_temporal_anchors_scenario_local(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact_root = root / "artifacts"
            artifact_root.mkdir()

            def expected(tool, args):
                return {"tool": tool, "args": args}

            def scenario(sid, tool, args):
                return {
                    "scenario_id": sid,
                    "split": "test",
                    "category": "temporal" if tool == "create_calendar_event" else "contact",
                    "workflow": "calendar" if tool == "create_calendar_event" else "detail",
                    "target_card_ids": [],
                    "turn_count": 1,
                    "turns": [{
                        "index": 1,
                        "user": "synthetic regression",
                        "note": "scenario-local scorer test",
                        "decision_kind": "TOOL",
                        "allowed_tool_sequences": [[tool]],
                        "expected_calls": [expected(tool, args)],
                        "forbidden_tools": [],
                    }],
                    "success": {"must_call": [tool]},
                }

            relative = {"start_time": {"cmp": "relative_datetime", "spec": "tomorrow_10:00"}}
            absolute = {"start_time": {"cmp": "canonical_datetime", "value": "2026-09-10T10:00"}}
            exact = {"card_id": {"cmp": "exact", "value": "S00001"}}
            scenarios = [
                scenario("anchor-seoul", "create_calendar_event", relative),
                scenario("anchor-year-end", "create_calendar_event", relative),
                scenario("anchor-missing", "create_calendar_event", relative),
                scenario("absolute", "create_calendar_event", absolute),
                scenario("non-calendar", "get_contact", exact),
            ]
            (root / "gold.json").write_text(
                json.dumps({"scenarios": scenarios}, ensure_ascii=False), encoding="utf-8"
            )

            actual_args = {
                "anchor-seoul": {"start_time": "2026-09-10T10:00"},
                "anchor-year-end": {"start_time": "2027-01-01T10:00"},
                # This must fail rather than inheriting the preceding scenario's anchor.
                "anchor-missing": {"start_time": "2026-09-10T10:00"},
                "absolute": {"start_time": "2026-09-10T10:00"},
                "non-calendar": {"card_id": "S00001"},
            }
            raw_rows = []
            for item in scenarios:
                sid = item["scenario_id"]
                tool = item["turns"][0]["expected_calls"][0]["tool"]
                raw_rows.append({
                    "scenario_id": sid,
                    "turns": [{
                        "index": 1,
                        "executed_tools": [tool],
                        "tool_arguments": [{"tool": tool, "arguments": actual_args[sid]}],
                        "selected_card_id": "",
                        "answer": "",
                        "new_compose_drafts": [],
                    }],
                    "final_selected_card_id": "",
                    "compose_draft_count": 0,
                    "calendar_draft_count": 0,
                    "mutated_cards": [],
                    "mutated_card_titles": [],
                })
            (root / "raw.jsonl").write_text(
                "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in raw_rows),
                encoding="utf-8",
            )

            index_rows = []
            anchors = {
                # The offset is part of the recorded runtime datetime. fromisoformat().date()
                # therefore uses the scenario's local calendar date rather than the host date.
                "anchor-seoul": "2026-09-09T23:30:00+09:00",
                "anchor-year-end": "2026-12-31T08:00:00-05:00",
            }
            for sid, runtime_datetime in anchors.items():
                scenario_dir = artifact_root / sid
                scenario_dir.mkdir()
                record = {
                    "phase": "send_return",
                    "model_tool_name": "get_current_datetime",
                    "raw_input": json.dumps({
                        "data": {
                            "date": runtime_datetime[:10],
                            "datetime": runtime_datetime,
                            "timezone": runtime_datetime[-6:],
                        }
                    }),
                }
                (scenario_dir / "native_protocol.local.jsonl").write_text(
                    json.dumps(record) + "\n", encoding="utf-8"
                )
                index_rows.append({"scenario_id": sid, "artifact_path": str(scenario_dir)})
            (root / "index.json").write_text(
                json.dumps({"scenarios": index_rows}), encoding="utf-8"
            )

            argv = [
                "score.py",
                "--repo", str(root),
                "--set", "gold.json",
                "--raw", "raw.jsonl",
                "--out", "score.json",
                "--artifact-index", str(root / "index.json"),
            ]
            with patch.object(sys, "argv", argv), redirect_stdout(io.StringIO()):
                self.assertEqual(score.main(), 0)

            report = json.loads((root / "score.json").read_text(encoding="utf-8"))
            headline = report["headline"]
            self.assertEqual(headline["argument_exact_match_pass"], 4)
            self.assertEqual(headline["argument_fields_pass"], 4)
            self.assertEqual(headline["correct_tool_calls"], 5)
            self.assertEqual(headline["tool_selection_correct"], 5)


if __name__ == "__main__":
    unittest.main()
