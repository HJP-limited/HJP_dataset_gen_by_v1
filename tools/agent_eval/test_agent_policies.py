from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

from agent_policies import (
    canonical_calendar_title,
    capability_violation,
    recipient_facing_goal,
)


FIXTURE = json.loads(
    (Path(__file__).parent / "fixtures" / "capability_policy_cases.json").read_text(encoding="utf-8")
)


class SharedFixtureParityTest(unittest.TestCase):
    """Reads the same fixture as agent-core/PolicyFixtureParityTest.kt.

    The Kotlin policies run on the device and these Python policies drive the Mac evaluation. If one
    side changes a rule without the other, one of the two suites fails instead of the evaluation
    silently describing a different agent.
    """

    def test_capability_violation_cases(self) -> None:
        cases = FIXTURE["capability_violation"]
        self.assertTrue(cases)
        for case in cases:
            with self.subTest(case["name"]):
                violation = capability_violation(
                    case["prompt"], {"intent": case["intent"], "action": case["action"]}
                )
                if case["expected_reason"] is None:
                    self.assertIsNone(violation)
                else:
                    self.assertIsNotNone(violation)
                    self.assertEqual(violation["reason"], case["expected_reason"])
                    self.assertEqual(violation["required_action"], "UNSUPPORTED")

    def test_calendar_title_cases(self) -> None:
        cases = FIXTURE["calendar_title"]
        self.assertTrue(cases)
        for case in cases:
            with self.subTest(case["name"]):
                self.assertEqual(
                    canonical_calendar_title(case["title"], case["prompt"]), case["expected"]
                )

    def test_recipient_facing_goal_cases(self) -> None:
        cases = FIXTURE["recipient_facing_goal"]
        self.assertTrue(cases)
        for case in cases:
            with self.subTest(case["name"]):
                self.assertEqual(recipient_facing_goal(case["goal"]), case["expected"])


class StagedSchemaParityTest(unittest.TestCase):
    """Reads the same staged-schema fixture as agent-contract/StagedSchemaParityTest.kt."""

    def setUp(self) -> None:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "litertlm_benchmark"))
        import hjp_staged_schemas

        self.schemas = hjp_staged_schemas
        self.expected = FIXTURE["staged_schema"]

    def test_intents_actions_and_recipient_types_match(self) -> None:
        self.assertEqual(list(self.schemas.INTENTS), self.expected["intents"])
        self.assertEqual(list(self.schemas.ACTIONS), self.expected["actions"])
        self.assertEqual(list(self.schemas.RECIPIENT_TYPES), self.expected["recipient_types"])

    def test_update_fields_match(self) -> None:
        self.assertEqual(set(self.schemas.UPDATE_FIELDS), set(self.expected["update_fields"]))


class AgentPoliciesTest(unittest.TestCase):
    def test_capability_veto_does_not_classify_supported_compose(self) -> None:
        model = {"intent": "COMPOSE_EMAIL", "action": "EXECUTE"}
        self.assertIsNone(capability_violation("메일 작성 화면을 열어 줘", model))
        self.assertIsNone(capability_violation("메일을 보내고 완료됐다고 말해 줘", model))

    def test_capability_vetoes_explicit_real_send_and_delete(self) -> None:
        self.assertEqual(
            capability_violation(
                "메일을 지금 바로 실제로 전송해 줘",
                {"intent": "COMPOSE_EMAIL", "action": "EXECUTE"},
            )["required_action"],
            "UNSUPPORTED",
        )
        self.assertEqual(
            capability_violation(
                "명함을 삭제해 줘",
                {"intent": "UPDATE_CONTACT", "action": "EXECUTE"},
            )["reason"],
            "DELETE_NOT_SUPPORTED",
        )

    def test_capability_policy_never_overrides_non_execute(self) -> None:
        self.assertIsNone(capability_violation(
            "명함을 삭제해 줘", {"intent": "UPDATE_CONTACT", "action": "CLARIFY"}
        ))

    def test_calendar_title_removes_only_grounded_generic_suffix(self) -> None:
        prompt = "내일 오후 2시에 계약 검토 일정을 만들어 주세요."
        self.assertEqual(canonical_calendar_title("계약 검토 일정", prompt), "계약 검토")
        self.assertEqual(canonical_calendar_title("고객 일정", prompt), "고객 일정")
        self.assertEqual(canonical_calendar_title("일정", prompt), "일정")
        staged_prompt = "내일 오후 2시에 분기 검토 캘린더 작성 단계까지 준비해 주세요."
        self.assertEqual(
            canonical_calendar_title("분기 검토 캘린더 작성 단계까지 준비", staged_prompt),
            "분기 검토",
        )

    def test_mixed_goal_keeps_message_fact_and_removes_agent_directive(self) -> None:
        self.assertEqual(
            recipient_facing_goal("확인했습니다. 전송 완료됐다고 말해줘."),
            "확인했습니다",
        )


if __name__ == "__main__":
    unittest.main()
