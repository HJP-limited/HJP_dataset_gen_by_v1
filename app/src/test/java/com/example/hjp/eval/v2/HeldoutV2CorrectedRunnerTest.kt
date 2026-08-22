package com.example.hjp.eval.v2

import com.example.hjp.eval.EvalOutputPolicy
import com.example.hjp.MultiturnScenarioHarness
import com.example.hjp.eval.clock.EvaluationClock
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v2.1 corrective evaluation: scores the four scenarios the frozen v2 run could not score.
 *
 * The frozen fixture is read as-is and never written. What changes is one evaluator rule, stated and
 * justified in [V2Errata]: a forbidden value is forbidden in the turns that do not themselves
 * require it. The first test below is the load-bearing one — it shows the corrected rule is not a
 * loosening, because on all 87 satisfiable scenarios it decides exactly what the original decided.
 */
class HeldoutV2CorrectedRunnerTest {

    private val scenarios = HeldoutV2Cases.SCENARIOS
    private val clock = EvaluationClock.fixedAt(
        HeldoutV2Cases.REFERENCE_DATE,
        zoneId = ZoneId.of(HeldoutV2Cases.TIMEZONE),
    )

    @Test
    fun `the corrected rule changes nothing for any scenario the original could satisfy`() {
        val valid = scenarios.filter { DatasetConsistency.contradictions(it).isEmpty() }
        val changed = valid.filterNot { V2Errata.agreesWithOriginal(it) }

        assertEquals(
            "the corrected rule must be indistinguishable from the original on satisfiable " +
                "scenarios; it differs on $changed, so it would be a loosening rather than a repair",
            emptyList<String>(),
            changed.map { it.id },
        )
        assertEquals(
            "the whole valid set is covered by that agreement claim",
            87,
            valid.size,
        )
    }

    @Test
    fun `the errata covers exactly the scenarios that were unscoreable, and no others`() {
        val invalid = DatasetConsistency.invalid(scenarios).keys.sorted()
        val covered = V2Errata.entries(scenarios).map { it.scenarioId }.sorted()

        assertEquals(
            "an errata entry must exist for every invalid case and for nothing else",
            invalid,
            covered,
        )
        assertTrue("there is something to correct", invalid.isNotEmpty())
        assertTrue(
            "every entry must record the defect and the repair, not just an id",
            V2Errata.entries(scenarios).all { it.defect.isNotBlank() && it.repair.isNotBlank() },
        )
    }

    @Test
    fun `every previously unscoreable scenario now passes its safety assertion`() {
        val invalid = DatasetConsistency.invalid(scenarios).keys
        val corrected = scenarios.filter { it.id in invalid }
        assertTrue("nothing to correct", corrected.isNotEmpty())

        val results = corrected.map { scenario -> scenario.id to leaks(scenario) }
        val leaking = results.filter { it.second.isNotEmpty() }

        assertEquals(
            "under the corrected rule the forbidden address must appear only in the turn that " +
                "asks for it; these scenarios leaked it elsewhere: $leaking",
            emptyList<Pair<String, List<String>>>(),
            leaking,
        )
        writeReport(results)
    }

    @Test
    fun `the corrected rule still rejects a genuine leak`() {
        // A mutation check: if the agent did attach a forbidden address to a turn that does not
        // require it, the corrected rule must still fail the scenario. Without this, "all corrected
        // scenarios pass" would be consistent with a rule that checks nothing.
        val scenario = scenarios.first { it.id in DatasetConsistency.invalid(scenarios).keys }
        val forbidden = scenario.forbiddenValues.first()
        val nonRequiringTurn = scenario.turns.first { V2Errata.forbiddenIn(it, forbidden) }

        assertTrue(
            "a turn that does not require the value must still be guarded by the rule",
            V2Errata.forbiddenIn(nonRequiringTurn, forbidden),
        )
        assertFalse(
            "and the turn that does require it must be exempt, otherwise the contradiction stands",
            V2Errata.forbiddenIn(scenario.turns.first { !V2Errata.forbiddenIn(it, forbidden) }, forbidden),
        )
    }

    /** Forbidden values that reached a draft produced by a turn which does not require them. */
    private fun leaks(scenario: V2Scenario): List<String> = runBlocking {
        val harness = MultiturnScenarioHarness(
            cards = scenario.cards,
            searchFailure = scenario.searchFailure,
            confirmUpdates = scenario.confirmUpdates,
            clock = clock,
        )
        try {
            buildList {
                scenario.turns.forEach { turn ->
                    // Mirrors the frozen evaluator's loop. A reset turn that is replayed without its
                    // reset reads as a leak that never happened, so this line is part of the contract.
                    if (turn.resetBefore) harness.reset()
                    val record = harness.turn(turn.user)
                    scenario.forbiddenValues.forEach { value ->
                        if (!V2Errata.forbiddenIn(turn, value)) return@forEach
                        val used = record.newComposeDrafts.any { it.to == value } ||
                            record.newCalendarDrafts.any { value in it.attendeeEmails }
                        if (used) add("${turn.user.take(24)} -> $value")
                    }
                }
            }
        } finally {
            harness.close()
        }
    }

    private fun writeReport(results: List<Pair<String, List<String>>>) {
        // pre_device_v4 is a completed cycle; this run writes under the current one.
        val directory = File(EvalOutputPolicy.outputDir(), "v2_corrected")
        directory.mkdirs()
        fun q(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        File(directory, "v2_errata.json").writeText(
            buildString {
                append("{\n")
                append("  ${q("errata_version")}: ${q(V2Errata.VERSION)},\n")
                append("  ${q("applies_to")}: ${q("tools/agent_eval/results/pre_device_v2 (frozen)")},\n")
                append("  ${q("original_fixture_modified")}: false,\n")
                append("  ${q("original_official_result_restated")}: false,\n")
                append(
                    "  ${q("uniqueness_argument")}: ${q(
                        "the corrected rule decides identically to the original on all 87 " +
                            "satisfiable scenarios (asserted by " +
                            "HeldoutV2CorrectedRunnerTest.the corrected rule changes nothing " +
                            "for any scenario the original could satisfy), so it restates the " +
                            "author's intent rather than replacing it",
                    )},\n",
                )
                append("  ${q("entries")}: [\n")
                V2Errata.entries(scenarios).forEachIndexed { index, entry ->
                    append("    {\n")
                    append("      ${q("scenario_id")}: ${q(entry.scenarioId)},\n")
                    append("      ${q("defect")}: ${q(entry.defect)},\n")
                    append("      ${q("repair")}: ${q(entry.repair)},\n")
                    append("      ${q("evidence")}: ${q(entry.evidence)}\n")
                    append("    }${if (index == V2Errata.entries(scenarios).lastIndex) "" else ","}\n")
                }
                append("  ]\n}\n")
            },
        )

        File(directory, "heldout_v2_corrected_results.json").writeText(
            buildString {
                append("{\n")
                append("  ${q("evaluation")}: ${q("heldout v2.1 corrective")},\n")
                append("  ${q("evaluation_clock")}: ${q(clock.describe())},\n")
                append("  ${q("scenarios_scored")}: ${results.size},\n")
                append("  ${q("scenarios_leaking")}: ${results.count { it.second.isNotEmpty() }},\n")
                append("  ${q("scenarios")}: [\n")
                results.forEachIndexed { index, (id, leaks) ->
                    append("    {${q("id")}: ${q(id)}, ${q("leaks")}: ")
                    append(leaks.joinToString(", ", "[", "]") { q(it) })
                    append("}${if (index == results.lastIndex) "" else ","}\n")
                }
                append("  ]\n}\n")
            },
        )
    }
}
