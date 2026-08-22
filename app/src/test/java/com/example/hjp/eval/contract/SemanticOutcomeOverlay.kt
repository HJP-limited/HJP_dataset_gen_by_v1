package com.example.hjp.eval.contract

import com.example.hjp.eval.FrozenHeldoutCases
import com.example.hjp.eval.v2.HeldoutV2Cases
import com.example.hjp.eval.v3.HeldoutV3Cases
import com.hjp.agent.contract.TurnOutcomeType

/**
 * The v4 expected typed outcome for a frozen v1–v3 turn, projected from that turn's own metadata.
 *
 * ## Why an overlay instead of a compatibility rule
 *
 * The first attempt at reconciling the frozen datasets with the v4 contract let *any* expected
 * `GENERAL_INFORMATION` be satisfied by an observed `CLARIFICATION_REQUIRED` as long as the turn ran
 * no tool. That is far too wide. A general-knowledge question, a greeting, a thank-you and an
 * unsupported request all run no tool, so a regression that started asking "who do you mean?" in
 * place of answering would have passed silently — the substitution was covering the very failure the
 * datasets exist to catch. An audit put the real missing-slot population at 22 turns against 55
 * accepted by that rule: 34 turns where the allowance was unjustified, and one genuine missing-slot
 * turn it still missed.
 *
 * So the projection is decided here, per turn, **before any run**, from what the frozen dataset says
 * about itself:
 *
 *  - v2 and v3 carry a typed `missingRequiredSlot` flag. That flag, and nothing else, selects a turn.
 *  - v1 predates the flag and records the same fact as a `missing_slot` entry in `secondaryTags`.
 *
 * Everything else keeps the outcome the dataset froze. No frozen file is edited, and the projection
 * never looks at what production actually did — a selector that reads the observation cannot fail the
 * observation.
 *
 * The audit numbers (v1 4, v2 7, v3 11) are asserted by `SemanticMigrationOverlayTest`, not encoded
 * here. This object computes the set; the test checks the count is what the audit found.
 */
object SemanticOutcomeOverlay {

    const val VERSION = "cycle8-overlay-1"

    /** One projected turn, with the frozen evidence that justified projecting it. */
    data class Entry(
        val datasetVersion: String,
        val caseId: String,
        val turnIndex: Int,
        val category: String,
        val userText: String,
        val frozenExpectedOutcome: TurnOutcomeType,
        val projectedExpectedOutcome: TurnOutcomeType,
        val frozenEvidence: String,
    )

    /** The tag v1 uses to say a turn is missing a required slot. */
    private const val V1_MISSING_SLOT_TAG = "missing_slot"

    private fun entriesV1(): List<Entry> = FrozenHeldoutCases.CASES.flatMap { case ->
        val spec = case.spec
        if (V1_MISSING_SLOT_TAG !in spec.secondaryTags) return@flatMap emptyList()
        spec.turns.mapIndexedNotNull { index, turn ->
            val frozen = turn.expectedOutcome ?: return@mapIndexedNotNull null
            // A missing-slot scenario can contain the completing turn too. Only the turn that runs
            // nothing is the one that asks, and only it is projected.
            if (turn.expectedTools.isNotEmpty() || turn.expectedSideEffects != 0) {
                return@mapIndexedNotNull null
            }
            Entry(
                datasetVersion = "v1",
                caseId = spec.id,
                turnIndex = index,
                category = spec.primaryCategory,
                userText = turn.user,
                frozenExpectedOutcome = frozen,
                projectedExpectedOutcome = TurnOutcomeType.CLARIFICATION_REQUIRED,
                frozenEvidence = "scenario secondaryTags contains \"$V1_MISSING_SLOT_TAG\"; " +
                    "the turn expects no tool and no side effect",
            )
        }
    }

    private fun entriesV2(): List<Entry> = HeldoutV2Cases.SCENARIOS.flatMap { scenario ->
        scenario.turns.mapIndexedNotNull { index, turn ->
            if (!turn.missingRequiredSlot) return@mapIndexedNotNull null
            Entry(
                datasetVersion = "v2",
                caseId = scenario.id,
                turnIndex = index,
                category = scenario.category,
                userText = turn.user,
                frozenExpectedOutcome = turn.outcome,
                projectedExpectedOutcome = TurnOutcomeType.CLARIFICATION_REQUIRED,
                frozenEvidence = "turn.missingRequiredSlot == true",
            )
        }
    }

    private fun entriesV3(): List<Entry> = HeldoutV3Cases.SCENARIOS.flatMap { scenario ->
        scenario.turns.mapIndexedNotNull { index, turn ->
            if (!turn.missingRequiredSlot) return@mapIndexedNotNull null
            Entry(
                datasetVersion = "v3",
                caseId = scenario.id,
                turnIndex = index,
                category = scenario.category,
                userText = turn.user,
                frozenExpectedOutcome = turn.outcome,
                projectedExpectedOutcome = TurnOutcomeType.CLARIFICATION_REQUIRED,
                frozenEvidence = "turn.missingRequiredSlot == true",
            )
        }
    }

    /** Every projected turn across the three frozen datasets. */
    val entries: List<Entry> by lazy { entriesV1() + entriesV2() + entriesV3() }

    private val byKey: Map<Triple<String, String, Int>, Entry> by lazy {
        entries.associateBy { Triple(it.datasetVersion, it.caseId, it.turnIndex) }
    }

    /**
     * The outcome this turn is expected to produce under the v4 contract.
     *
     * Returns [frozenExpected] unchanged for every turn the overlay does not cover, which is the
     * overwhelming majority — the overlay is a projection of specific turns, not a new contract for
     * the dataset.
     */
    fun expectedOutcome(
        datasetVersion: String,
        caseId: String,
        turnIndex: Int,
        frozenExpected: TurnOutcomeType,
    ): TurnOutcomeType =
        byKey[Triple(datasetVersion, caseId, turnIndex)]?.projectedExpectedOutcome ?: frozenExpected

    fun covers(datasetVersion: String, caseId: String, turnIndex: Int): Boolean =
        Triple(datasetVersion, caseId, turnIndex) in byKey

    fun countsByDataset(): Map<String, Int> =
        entries.groupingBy { it.datasetVersion }.eachCount().toSortedMap()
}
