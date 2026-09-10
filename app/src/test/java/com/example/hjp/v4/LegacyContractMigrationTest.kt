package com.example.hjp.v4

import com.example.hjp.eval.EvalOutputPolicy
import com.example.hjp.eval.FrozenHeldoutCases
import com.example.hjp.eval.contract.LegacyOutcomeMigrationLog
import com.example.hjp.eval.contract.SemanticOutcomeOverlay
import com.example.hjp.eval.v2.HeldoutV2Cases
import com.example.hjp.eval.v3.HeldoutV3Cases
import com.hjp.agent.contract.TurnOutcomeType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit of how the first reconciliation attempt chose which frozen turns to reinterpret, and
 * what the semantic overlay chooses instead.
 *
 * The earlier approach inferred "this turn is a clarification" from the *presence of an answer
 * assertion* on a turn that ran no tool. That reads as a reasonable proxy and is not one: a
 * general-knowledge question also states what its answer must contain, and also runs no tool. Across
 * v1–v3 it selected 55 turns where the frozen metadata marks 22 — 34 turns whose reinterpretation
 * nothing justified, while still missing one real missing-slot turn, because that turn's assertion
 * happened to be a must-*not*-contain.
 *
 * This class keeps the comparison as a permanent record. It does not assert the old rule was right;
 * it asserts the difference between the two selectors is exactly what the audit found, so a change
 * that quietly widens the overlay again shows up here rather than in a passing evaluation.
 */
class LegacyContractMigrationTest {

    /** What the audit found. */
    private val semanticTotal = 22
    private val heuristicTotal = 55
    private val falsePositives = 34
    private val falseNegatives = 1

    private data class Key(val dataset: String, val caseId: String, val turnIndex: Int)

    /**
     * The superseded selector, reproduced exactly, so the audit stays a measurement rather than a
     * claim: "expected GENERAL_INFORMATION, ran no tool, had some answer assertion".
     */
    private fun heuristicSelection(): List<Key> = buildList {
        FrozenHeldoutCases.CASES.forEach { case ->
            val spec = case.spec
            spec.turns.forEachIndexed { index, turn ->
                if (turn.expectedOutcome != TurnOutcomeType.GENERAL_INFORMATION) return@forEachIndexed
                if (turn.expectedTools.isNotEmpty() || turn.expectedSideEffects != 0) return@forEachIndexed
                if ((turn.answerMustContain + turn.answerMustNotContain).isEmpty()) return@forEachIndexed
                add(Key("v1", spec.id, index))
            }
        }
        HeldoutV2Cases.SCENARIOS.forEach { s ->
            s.turns.forEachIndexed { index, turn ->
                if (turn.outcome != TurnOutcomeType.GENERAL_INFORMATION) return@forEachIndexed
                if (turn.tools.isNotEmpty() || turn.sideEffects != 0) return@forEachIndexed
                if (turn.answerContains.isEmpty()) return@forEachIndexed
                add(Key("v2", s.id, index))
            }
        }
        HeldoutV3Cases.SCENARIOS.forEach { s ->
            s.turns.forEachIndexed { index, turn ->
                if (turn.outcome != TurnOutcomeType.GENERAL_INFORMATION) return@forEachIndexed
                if (turn.tools.isNotEmpty() || turn.sideEffects != 0) return@forEachIndexed
                if (turn.answerContains.isEmpty()) return@forEachIndexed
                add(Key("v3", s.id, index))
            }
        }
    }

    private fun semanticSelection(): List<Key> =
        SemanticOutcomeOverlay.entries.map { Key(it.datasetVersion, it.caseId, it.turnIndex) }

    @Test
    fun `the semantic overlay selects the audited population`() {
        assertEquals(semanticTotal, semanticSelection().size)
        assertEquals(
            mapOf("v1" to 4, "v2" to 7, "v3" to 11).toSortedMap(),
            SemanticOutcomeOverlay.countsByDataset(),
        )
    }

    @Test
    fun `the superseded heuristic still selects the number the audit reported`() {
        // If this drifts, the audit figures in the report no longer describe this repository and the
        // comparison below is measuring something else.
        assertEquals(heuristicTotal, heuristicSelection().size)
    }

    @Test
    fun `the audited false positives are excluded by the overlay`() {
        val semantic = semanticSelection().toSet()
        val excluded = heuristicSelection().filterNot { it in semantic }

        assertEquals(
            "the overlay must drop exactly the turns the heuristic over-selected",
            falsePositives,
            excluded.size,
        )
        // And droppable for a stated reason rather than by count: every excluded v2/v3 turn must be
        // one the frozen metadata does *not* mark as missing a required slot.
        val v2 = HeldoutV2Cases.SCENARIOS.associateBy { it.id }
        val v3 = HeldoutV3Cases.SCENARIOS.associateBy { it.id }
        val wronglyExcluded = excluded.filter { key ->
            when (key.dataset) {
                "v2" -> v2.getValue(key.caseId).turns[key.turnIndex].missingRequiredSlot
                "v3" -> v3.getValue(key.caseId).turns[key.turnIndex].missingRequiredSlot
                else -> false
            }
        }
        assertEquals(
            "no turn the dataset marks as missing a required slot may be dropped",
            emptyList<Key>(),
            wronglyExcluded,
        )
    }

    @Test
    fun `the audited false negative is included by the overlay`() {
        val heuristic = heuristicSelection().toSet()
        val added = semanticSelection().filterNot { it in heuristic }

        assertEquals(
            "the overlay must pick up the missing-slot turn the heuristic could not see",
            falseNegatives,
            added.size,
        )
        assertTrue(
            "and it is a v1 turn, which is where the must-not-contain phrasing hid it: $added",
            added.all { it.dataset == "v1" },
        )
    }

    @Test
    fun `every projected turn cites frozen metadata as its reason`() {
        val unjustified = SemanticOutcomeOverlay.entries.filter {
            !it.frozenEvidence.contains("missingRequiredSlot") &&
                !it.frozenEvidence.contains("missing_slot")
        }
        assertEquals(
            "a projection with no frozen evidence behind it is the defect this replaces",
            emptyList<String>(),
            unjustified.map { "${it.datasetVersion}/${it.caseId}#${it.turnIndex}" },
        )
        writeAudit()
    }

    private fun writeAudit() {
        // The cycle 8 contract directory is now historical; this run writes under the current cycle.
        val directory = File(EvalOutputPolicy.outputDir(), "contract")
        directory.mkdirs()
        fun q(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        val semantic = semanticSelection().toSet()
        val heuristic = heuristicSelection()
        val excluded = heuristic.filterNot { it in semantic }
            .sortedWith(compareBy({ it.dataset }, { it.caseId }, { it.turnIndex }))
        val added = semanticSelection().filterNot { it in heuristic.toSet() }

        fun keyRows(keys: List<Key>) = buildString {
            keys.forEachIndexed { i, k ->
                append("    {${q("dataset")}: ${q(k.dataset)}, ${q("case_id")}: ${q(k.caseId)}, ")
                append("${q("turn_index")}: ${k.turnIndex}}")
                append(if (i == keys.lastIndex) "\n" else ",\n")
            }
        }

        File(directory, "legacy_false_positive_audit.json").writeText(
            buildString {
                append("{\n")
                append("  ${q("what")}: ${q(
                    "the superseded answer-assertion heuristic compared against the frozen semantic " +
                        "metadata selector",
                )},\n")
                append("  ${q("superseded_selector")}: ${q(
                    "expected GENERAL_INFORMATION, no tool, no side effect, some answer assertion",
                )},\n")
                append("  ${q("semantic_selector")}: ${q(
                    "v2/v3 turn.missingRequiredSlot == true; v1 scenario secondaryTags contains missing_slot",
                )},\n")
                append("  ${q("superseded_count")}: ${heuristic.size},\n")
                append("  ${q("semantic_count")}: ${semantic.size},\n")
                append("  ${q("false_positives_removed")}: ${excluded.size},\n")
                append("  ${q("false_negatives_recovered")}: ${added.size},\n")
                append("  ${q("original_datasets_modified")}: false,\n")
                append("  ${q("false_positive_turns")}: [\n")
                append(keyRows(excluded))
                append("  ],\n")
                append("  ${q("false_negative_turns")}: [\n")
                append(keyRows(added))
                append("  ]\n}\n")
            },
        )

        File(directory, "semantic_migration_overlay.json").writeText(
            buildString {
                append("{\n")
                append("  ${q("overlay_version")}: ${q(SemanticOutcomeOverlay.VERSION)},\n")
                append("  ${q("original_dataset_modified")}: false,\n")
                append("  ${q("selector_reads_observations")}: false,\n")
                append("  ${q("count")}: ${SemanticOutcomeOverlay.entries.size},\n")
                append("  ${q("by_dataset")}: {")
                append(
                    SemanticOutcomeOverlay.countsByDataset().entries
                        .joinToString(", ") { "${q(it.key)}: ${it.value}" },
                )
                append("},\n")
                append("  ${q("entries")}: [\n")
                SemanticOutcomeOverlay.entries.forEachIndexed { i, e ->
                    append("    {\n")
                    append("      ${q("dataset_version")}: ${q(e.datasetVersion)},\n")
                    append("      ${q("case_id")}: ${q(e.caseId)},\n")
                    append("      ${q("turn_index")}: ${e.turnIndex},\n")
                    append("      ${q("category")}: ${q(e.category)},\n")
                    append("      ${q("user_text")}: ${q(e.userText)},\n")
                    append("      ${q("frozen_expected_outcome")}: ${q(e.frozenExpectedOutcome.name)},\n")
                    append("      ${q("v4_projected_expected_outcome")}: ${q(e.projectedExpectedOutcome.name)},\n")
                    append("      ${q("frozen_evidence")}: ${q(e.frozenEvidence)}\n")
                    append("    }${if (i == SemanticOutcomeOverlay.entries.lastIndex) "" else ","}\n")
                }
                append("  ]\n}\n")
            },
        )

        // The projection is the validation: it is decided from frozen metadata, so it can be
        // written without running anything. The runtime log is additive evidence and may legitimately
        // be empty in a JVM fork that ran no frozen suite, which is why it is a separate file.
        File(directory, "migration_validation.json").writeText(
            buildString {
                append("{\n")
                append("  ${q("overlay_version")}: ${q(SemanticOutcomeOverlay.VERSION)},\n")
                append("  ${q("validated_by")}: ${q(
                    "LegacyContractMigrationTest and SemanticMigrationOverlayTest",
                )},\n")
                append("  ${q("semantic_count")}: ${SemanticOutcomeOverlay.entries.size},\n")
                append("  ${q("expected_by_audit")}: {${q("v1")}: 4, ${q("v2")}: 7, ${q("v3")}: 11, ${q("total")}: 22},\n")
                append("  ${q("observed_by_dataset")}: {")
                append(
                    SemanticOutcomeOverlay.countsByDataset().entries
                        .joinToString(", ") { "${q(it.key)}: ${it.value}" },
                )
                append("},\n")
                append("  ${q("selector_reads_observations")}: false,\n")
                append("  ${q("general_substitution_present")}: false,\n")
                append("  ${q("original_datasets_modified")}: false\n")
                append("}\n")
            },
        )
        LegacyOutcomeMigrationLog.writeTo(File(directory, "migration_runtime_observations.json"))
    }
}
