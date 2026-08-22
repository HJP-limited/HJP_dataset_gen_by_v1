package com.example.hjp.v4

import com.example.hjp.eval.FrozenHeldoutCases
import com.example.hjp.eval.contract.EvaluationContractVersion
import com.example.hjp.eval.contract.OutcomeContract
import com.example.hjp.eval.contract.SemanticOutcomeOverlay
import com.example.hjp.eval.v2.HeldoutV2Cases
import com.example.hjp.eval.v3.HeldoutV3Cases
import com.hjp.agent.contract.TurnOutcomeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection from frozen v1–v3 expectations to the v4 contract, and the ways it must not work.
 *
 * The rule this replaces accepted an observed `CLARIFICATION_REQUIRED` against any expected
 * `GENERAL_INFORMATION` on a turn that ran no tool. An audit of the frozen metadata put the real
 * missing-slot population at 22 turns while that rule accepted 55 — so 34 turns had an allowance
 * nothing justified, and a regression that started asking "who do you mean?" instead of answering a
 * general question would have passed.
 *
 * These tests hold three separate things:
 *
 *  1. the projected set is exactly the turns the frozen metadata marks as missing a required slot;
 *  2. the selector reads only frozen metadata, never what a run produced;
 *  3. the general substitution is gone — a general-information turn that returns a clarification
 *     fails, tools or no tools.
 */
class SemanticMigrationOverlayTest {

    /** What the cycle 7 audit found in the frozen datasets. */
    private val auditedCounts = mapOf("v1" to 4, "v2" to 7, "v3" to 11)
    private val auditedTotal = 22

    @Test
    fun `the projected set is exactly the audited missing-slot population`() {
        assertEquals(
            "the overlay must select the turns the frozen metadata marks, and only those",
            auditedCounts.toSortedMap(),
            SemanticOutcomeOverlay.countsByDataset(),
        )
        assertEquals(auditedTotal, SemanticOutcomeOverlay.entries.size)
    }

    @Test
    fun `v2 and v3 selection comes from the typed flag alone`() {
        val v2FromFlag = HeldoutV2Cases.SCENARIOS.sumOf { s -> s.turns.count { it.missingRequiredSlot } }
        val v3FromFlag = HeldoutV3Cases.SCENARIOS.sumOf { s -> s.turns.count { it.missingRequiredSlot } }

        assertEquals(
            "v2's projected count must equal the number of turns carrying missingRequiredSlot",
            v2FromFlag,
            SemanticOutcomeOverlay.countsByDataset()["v2"],
        )
        assertEquals(v3FromFlag, SemanticOutcomeOverlay.countsByDataset()["v3"])
        assertTrue(
            "every v2/v3 entry must cite the typed flag as its evidence",
            SemanticOutcomeOverlay.entries
                .filter { it.datasetVersion != "v1" }
                .all { it.frozenEvidence.contains("missingRequiredSlot") },
        )
    }

    @Test
    fun `v1 selection comes from the frozen missing_slot tag`() {
        val v1Entries = SemanticOutcomeOverlay.entries.filter { it.datasetVersion == "v1" }
        val tagged = FrozenHeldoutCases.CASES
            .map { it.spec }
            .filter { "missing_slot" in it.secondaryTags }
            .map { it.id }
            .toSet()

        assertTrue("v1 must contribute entries", v1Entries.isNotEmpty())
        assertEquals(
            "every v1 entry must come from a scenario the frozen dataset tagged missing_slot",
            emptyList<String>(),
            v1Entries.map { it.caseId }.filterNot { it in tagged },
        )
        assertTrue(
            "and each must cite the tag, not the presence of an answer assertion — inferring " +
                "clarification from answerMustContain is what produced the 34 false positives",
            v1Entries.all { it.frozenEvidence.contains("missing_slot") },
        )
    }

    @Test
    fun `the projected set includes a v1 compose-without-recipient turn`() {
        // The rule this replaces missed one: a v1 compose turn with no recipient. It is the reason
        // the old count could be both too large and too small at the same time.
        val v1 = SemanticOutcomeOverlay.entries.filter { it.datasetVersion == "v1" }
        val composeTurns = v1.filter { entry ->
            val spec = FrozenHeldoutCases.CASES.map { it.spec }.first { it.id == entry.caseId }
            "compose" in spec.secondaryTags || "compose" in spec.primaryCategory
        }
        assertTrue(
            "v1's missing-slot population must include its compose-without-recipient case; " +
                "selected v1 cases were ${v1.map { it.caseId }}",
            composeTurns.isNotEmpty(),
        )
    }

    @Test
    fun `general information, greetings, thanks and unsupported requests are never projected`() {
        // The 34 false positives were turns of exactly these kinds.
        val wronglyProjected = mutableListOf<String>()
        HeldoutV2Cases.SCENARIOS.forEach { s ->
            s.turns.forEachIndexed { index, turn ->
                val excluded = turn.generalInformation || turn.unsupportedRequest || turn.quotedRecall
                if (excluded && SemanticOutcomeOverlay.covers("v2", s.id, index)) {
                    wronglyProjected += "v2/${s.id}#$index"
                }
            }
        }
        HeldoutV3Cases.SCENARIOS.forEach { s ->
            s.turns.forEachIndexed { index, turn ->
                val excluded = turn.generalInformation || turn.unsupportedRequest || turn.quotedRecall
                if (excluded && SemanticOutcomeOverlay.covers("v3", s.id, index)) {
                    wronglyProjected += "v3/${s.id}#$index"
                }
            }
        }
        assertEquals(
            "a turn the dataset marks as general information, unsupported or a recall is not a " +
                "missing-slot turn and must keep its frozen expectation",
            emptyList<String>(),
            wronglyProjected,
        )
    }

    @Test
    fun `every projected turn ran no tool and caused no side effect in its own expectations`() {
        val v2 = HeldoutV2Cases.SCENARIOS.associateBy { it.id }
        val v3 = HeldoutV3Cases.SCENARIOS.associateBy { it.id }
        val offenders = SemanticOutcomeOverlay.entries.filter { entry ->
            when (entry.datasetVersion) {
                "v2" -> v2.getValue(entry.caseId).turns[entry.turnIndex]
                    .let { it.tools.isNotEmpty() || it.sideEffects != 0 }
                "v3" -> v3.getValue(entry.caseId).turns[entry.turnIndex]
                    .let { it.tools.isNotEmpty() || it.sideEffects != 0 }
                else -> false
            }
        }
        assertEquals(
            "a turn that is expected to run a tool is not a turn that stopped to ask",
            emptyList<String>(),
            offenders.map { "${it.datasetVersion}/${it.caseId}#${it.turnIndex}" },
        )
    }

    @Test
    fun `the overlay leaves every other turn's frozen expectation untouched`() {
        val unchanged = SemanticOutcomeOverlay.expectedOutcome(
            "v3", "definitely-not-a-real-case-id", 0, TurnOutcomeType.GENERAL_INFORMATION,
        )
        assertEquals(
            "an uncovered turn keeps exactly what the dataset froze",
            TurnOutcomeType.GENERAL_INFORMATION,
            unchanged,
        )
        val projected = SemanticOutcomeOverlay.entries.first()
        assertEquals(
            TurnOutcomeType.CLARIFICATION_REQUIRED,
            SemanticOutcomeOverlay.expectedOutcome(
                projected.datasetVersion, projected.caseId, projected.turnIndex,
                projected.frozenExpectedOutcome,
            ),
        )
    }

    // ---- the substitution must be gone -------------------------------------------------------------

    @Test
    fun `a general-information turn that returns a clarification now fails`() {
        // The characterization of the defect: this used to pass under LEGACY_V1_V3 because no tool
        // ran. It must fail under every contract version.
        EvaluationContractVersion.entries.forEach { version ->
            assertFalse(
                "$version must not accept a clarification where a general answer was expected",
                OutcomeContract.matches(
                    version,
                    TurnOutcomeType.GENERAL_INFORMATION,
                    TurnOutcomeType.CLARIFICATION_REQUIRED,
                    emptyList(),
                ),
            )
        }
    }

    @Test
    fun `comparison is exact for every pair of outcomes`() {
        val all = TurnOutcomeType.entries
        val accepted = mutableListOf<String>()
        EvaluationContractVersion.entries.forEach { version ->
            all.forEach { expected ->
                all.forEach { observed ->
                    if (expected == observed) return@forEach
                    if (OutcomeContract.matches(version, expected, observed, emptyList())) {
                        accepted += "$version: expected=$expected observed=$observed"
                    }
                }
            }
        }
        assertEquals(
            "no outcome may stand in for another; the projection happens before comparison, not " +
                "inside it",
            emptyList<String>(),
            accepted,
        )
    }

    @Test
    fun `the selector does not read the observation`() {
        // Same overlay, queried with two different "observed" contexts — there is no parameter for
        // one, which is the structural guarantee. This test states it so a future signature change
        // that adds one is caught.
        val first = SemanticOutcomeOverlay.entries.map { it.datasetVersion to it.caseId }
        val second = SemanticOutcomeOverlay.entries.map { it.datasetVersion to it.caseId }
        assertEquals("the projected set is a pure function of the frozen data", first, second)
        assertEquals(auditedTotal, first.size)
    }
}
