package com.example.hjp.v4

import com.example.hjp.eval.FrozenHeldoutCases
import com.example.hjp.eval.TurnSpec
import com.example.hjp.eval.VisibleGeneralizationCases
import com.example.hjp.eval.contract.UnresolvedTargetOutcomeOverlay
import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.contract.TurnOutcomeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unresolved-target projection must be narrow, and must be a tightening.
 *
 * A projection that silently widened would be worse than the mis-typing it corrects: it would accept
 * a turn that acted when it should have asked. So this pins the exact population it selects, proves
 * every selector condition is load-bearing by removing them one at a time, and proves the projection
 * only ever replaces a "showed something" outcome with the stricter "asked something".
 */
class UnresolvedTargetOverlayAuditTest {

    private val base = TurnSpec(
        user = "옥다래에게 제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘.",
        expectedAct = DialogueAct.ACTION_COMPOSE,
        expectedOutcome = TurnOutcomeType.CONTACT_DETAIL_SHOWN,
        expectedTools = listOf("search_contacts", "get_contact"),
        forbiddenTools = setOf("open_compose"),
        expectedSideEffects = 0,
    )

    @Test
    fun `the canonical shape is selected`() {
        assertTrue(UnresolvedTargetOutcomeOverlay.declaresAnUnresolvableActionTarget(base))
        assertEquals(
            TurnOutcomeType.CLARIFICATION_REQUIRED,
            UnresolvedTargetOutcomeOverlay.expectedOutcome(base, base.expectedOutcome!!),
        )
    }

    @Test
    fun `every selector condition is load-bearing`() {
        val weakened = listOf(
            "not an action act" to base.copy(expectedAct = DialogueAct.CONTACT_SEARCH),
            "already a clarification" to
                base.copy(expectedOutcome = TurnOutcomeType.CLARIFICATION_REQUIRED),
            "a side effect is expected" to base.copy(expectedSideEffects = 1),
            "the action tool is not forbidden" to base.copy(forbiddenTools = emptySet()),
            "the action tool is expected to run" to
                base.copy(expectedTools = listOf("search_contacts", "get_contact", "open_compose")),
        )
        weakened.forEach { (why, turn) ->
            assertFalse(
                "the overlay still fired when $why",
                UnresolvedTargetOutcomeOverlay.declaresAnUnresolvableActionTarget(turn),
            )
            assertEquals(
                "the overlay changed an outcome it does not cover ($why)",
                turn.expectedOutcome,
                turn.expectedOutcome?.let {
                    UnresolvedTargetOutcomeOverlay.expectedOutcome(turn, it)
                },
            )
        }
    }

    @Test
    fun `no projected turn expects a side effect or an action tool`() {
        val covered = UnresolvedTargetOutcomeOverlay.entriesFor("v1", FrozenHeldoutCases.CASES.map { it.spec }) +
            UnresolvedTargetOutcomeOverlay.entriesFor("visible", VisibleGeneralizationCases.ALL)
        assertTrue("the overlay covers nothing at all", covered.isNotEmpty())
        covered.forEach { entry ->
            assertEquals(
                "${entry.caseId} was projected to something other than a clarification",
                TurnOutcomeType.CLARIFICATION_REQUIRED, entry.projectedExpectedOutcome,
            )
            assertTrue(
                "${entry.caseId} was projected away from an outcome that is not a display",
                entry.frozenExpectedOutcome == TurnOutcomeType.CONTACT_SELECTED ||
                    entry.frozenExpectedOutcome == TurnOutcomeType.CONTACT_DETAIL_SHOWN,
            )
        }
    }

    /**
     * The audited population, pinned.
     *
     * These numbers are what the tree contained when the projection was introduced. A change to
     * either number means a dataset gained or lost a declared must-not-act turn, which is a thing a
     * reader should be told rather than something that slides through.
     */
    @Test
    fun `the projected population is the audited one`() {
        assertEquals(
            5,
            UnresolvedTargetOutcomeOverlay
                .entriesFor("v1", FrozenHeldoutCases.CASES.map { it.spec }).size,
        )
        assertEquals(
            14,
            UnresolvedTargetOutcomeOverlay
                .entriesFor("visible", VisibleGeneralizationCases.ALL).size,
        )
    }
}
