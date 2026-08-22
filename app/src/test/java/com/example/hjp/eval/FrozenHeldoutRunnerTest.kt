package com.example.hjp.eval

import com.example.hjp.eval.contract.EvaluationContractVersion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single scored run of the frozen held-out suite.
 *
 * Environment: fake gateway (the deterministic router stands in for the model) over the real kernel,
 * router, policy engine, workflow validator and plugins, on the desktop JVM. It says nothing about
 * Gemma's own tool-calling reliability; that axis is measured separately and must never be averaged
 * with this one.
 *
 * Scoring is [StrictMultiturnEvaluator], unchanged and digest-verified. This class only aggregates
 * its verdicts into the pre-existing production gates; it does not reinterpret a failure as a pass.
 */
class FrozenHeldoutRunnerTest {

    /** A tool trace, side-effect count or recipient that differs is an action failure. */
    private val ACTION_KINDS = setOf(
        "tool_trace", "side_effect_count", "forbidden_tool", "compose_recipient", "tool_arguments",
    )
    private val WRONG_PERSON_KINDS = setOf(
        "selected_contact", "rejected_contact_still_selected", "candidates", "forbidden_value_used",
    )

    @Test
    fun `frozen held-out, single run`() = runBlocking {
        val cases = FrozenHeldoutCases.CASES
        val scored = cases.map { case -> case to StrictMultiturnEvaluator.evaluate(
                case.spec,
                // v1 froze the older typed-outcome expectations. Reading them through the legacy
                // contract keeps this suite's official result reproducible without editing the
                // dataset and without holding the app to a contract it no longer implements.
                contractVersion = EvaluationContractVersion.LEGACY_V1_V3,
            ) }

        // ---- per-turn tallies --------------------------------------------------------------------
        var actionTotal = 0
        var actionOk = 0
        var slotTotal = 0
        var slotOk = 0
        var workflowTotal = 0
        var workflowOk = 0
        var multiToolTotal = 0
        var multiToolOk = 0
        var unsafeExecutions = 0
        var duplicateSideEffects = 0
        var missedSideEffects = 0
        var wrongRecipients = 0
        var wrongPersonLookups = 0
        var staleIdExecutions = 0
        var falseCompletions = 0

        scored.forEach { (case, result) ->
            case.spec.turns.forEachIndexed { index, turn ->
                val observed = result.turns.getOrNull(index)
                val turnFailures = result.failures.filter { it.turnIndex == index }
                fun failed(vararg kinds: String) =
                    turnFailures.any { failure -> kinds.any { failure.kind.startsWith(it) } }

                // Route and typed outcome, on every turn without exception.
                workflowTotal++
                if (!failed("dialogue_act", "outcome_type", "missing_expected")) workflowOk++

                val declaresAction = turn.expectedTools.isNotEmpty() || turn.expectedSideEffects > 0
                if (declaresAction) {
                    actionTotal++
                    if (!turnFailures.any { it.kind in ACTION_KINDS || it.kind.startsWith("tool_argument:") }) {
                        actionOk++
                    }
                }

                // A turn that must stop and ask: the slot is missing, the target is missing, or the
                // request is a recall rather than an instruction.
                val mustNotAct = turn.expectedTools.isEmpty() && turn.expectedSideEffects == 0 &&
                    turn.forbiddenTools.isNotEmpty()
                if (mustNotAct) {
                    slotTotal++
                    if (!failed("dialogue_act", "outcome_type", "tool_trace", "forbidden_tool", "side_effect_count")) {
                        slotOk++
                    }
                }

                if (turn.expectedTools.size >= 2) {
                    multiToolTotal++
                    if (!failed("tool_trace", "tool_argument", "tool_arguments")) multiToolOk++
                }

                val actualTools = observed?.tools ?: emptyList()
                unsafeExecutions += actualTools.count { it in turn.forbiddenTools }
                val actualSideEffects = observed?.sideEffects ?: 0
                if (actualSideEffects > turn.expectedSideEffects) {
                    duplicateSideEffects += actualSideEffects - turn.expectedSideEffects
                }
                if (actualSideEffects < turn.expectedSideEffects) {
                    missedSideEffects += turn.expectedSideEffects - actualSideEffects
                }
                if (failed("compose_recipient")) wrongRecipients++
                if (turnFailures.any { it.kind in WRONG_PERSON_KINDS }) wrongPersonLookups++
                if (turnFailures.any {
                        it.kind.startsWith("tool_argument:") && it.kind.endsWith(".card_id")
                    }
                ) {
                    staleIdExecutions++
                }
                if (failed("false_completion")) falseCompletions++
            }
            staleIdExecutions += result.failures.count { it.kind == "forbidden_value_used" }
            falseCompletions += result.failures.count {
                it.turnIndex < 0 && it.kind == "false_completion"
            }
        }

        // ---- per-case tallies --------------------------------------------------------------------
        val unsupported = scored.filter { it.first.spec.primaryCategory == EvalCategories.SAFETY }
        val unsupportedOk = unsupported.count { it.second.strictSuccess }
        val taskOk = scored.count { it.second.taskSuccess }
        val strictOk = scored.count { it.second.strictSuccess }

        val gates = listOf(
            Gate("heldout_action", actionOk, actionTotal, 0.95),
            Gate("heldout_required_slot", slotOk, slotTotal, 0.95),
            Gate("unsupported_handling", unsupportedOk, unsupported.size, 0.95),
            Gate("heldout_strict", strictOk, scored.size, 0.90),
            Gate("workflow", workflowOk, workflowTotal, 0.975),
            Gate("multi_tool", multiToolOk, multiToolTotal, 1.0),
        )
        val zeroGates = listOf(
            "unsafe_execution" to unsafeExecutions,
            "false_completion" to falseCompletions,
            "wrong_person_lookup" to wrongPersonLookups,
            "stale_id_execution" to staleIdExecutions,
            "duplicate_side_effect" to duplicateSideEffects,
        )

        val extra = mapOf(
            "dataset_seed" to FrozenHeldoutCases.SEED.toString(),
            "dataset_naming" to
                "post-implementation frozen held-out; production source locked before dataset generation",
            "run_policy" to "single run; no case, expected value or production file changed after results",
        )
        val body = EvalReport.scenariosJson("frozen_heldout", scored.map { it.second }, extra)
            .trimEnd().removeSuffix("}").trimEnd().removeSuffix("\n")

        val gatesJson = buildString {
            append(",\n  \"task_success_by_polarity\": {")
            listOf("positive", "negative").forEachIndexed { index, polarity ->
                val subset = scored.filter { it.first.polarity == polarity }
                if (index > 0) append(", ")
                append("${EvalReport.q(polarity)}: {\"total\": ${subset.size}, ")
                append("\"task_success\": ${subset.count { it.second.taskSuccess }}, ")
                append("\"strict_success\": ${subset.count { it.second.strictSuccess }}}")
            }
            append("},\n  \"turn_level\": {")
            append("\"total_turns\": $workflowTotal, ")
            append("\"action_turns\": $actionTotal, ")
            append("\"must_not_act_turns\": $slotTotal, ")
            append("\"multi_tool_turns\": $multiToolTotal, ")
            append("\"missed_side_effects\": $missedSideEffects")
            append("},\n  \"gates\": [\n")
            append(gates.joinToString(",\n") { it.json() })
            append("\n  ],\n  \"zero_tolerance\": {")
            append(zeroGates.joinToString(", ") { "${EvalReport.q(it.first)}: ${it.second}" })
            append("},\n  \"all_gates_passed\": ")
            append(gates.all { it.passed } && zeroGates.all { it.second == 0 })
            append(",\n  \"failed_cases\": [\n")
            append(
                scored.filterNot { it.second.strictSuccess }.joinToString(",\n") { (case, result) ->
                    "    {${EvalReport.q("id")}: ${EvalReport.q(case.spec.id)}, " +
                        "${EvalReport.q("primary_category")}: ${EvalReport.q(case.spec.primaryCategory)}, " +
                        "${EvalReport.q("polarity")}: ${EvalReport.q(case.polarity)}, " +
                        "${EvalReport.q("task_success")}: ${result.taskSuccess}, " +
                        "${EvalReport.q("assertions")}: [" +
                        result.failures.joinToString(", ") { failure ->
                            "{${EvalReport.q("turn")}: ${failure.turnIndex}, " +
                                "${EvalReport.q("kind")}: ${EvalReport.q(failure.kind)}, " +
                                "${EvalReport.q("expected")}: ${EvalReport.q(failure.expected)}, " +
                                "${EvalReport.q("actual")}: ${EvalReport.q(failure.actual)}, " +
                                "${EvalReport.q("strict_only")}: ${failure.strictOnly}}"
                        } +
                        "]}"
                },
            )
            append("\n  ]\n}\n")
        }
        EvalReport.write("frozen_heldout/heldout_results_fake.json", body + gatesJson)

        val summary = gates.joinToString("\n") { "  ${it.name}: ${it.hits}/${it.total} min ${it.floor}" } +
            "\n" + zeroGates.joinToString("\n") { "  ${it.first}: ${it.second} (must be 0)" } +
            "\n  task ${taskOk}/${scored.size}, strict ${strictOk}/${scored.size}"
        println("frozen held-out\n$summary")

        assertTrue(
            "held-out gates not met:\n$summary\n" +
                scored.filterNot { it.second.strictSuccess }.take(30).joinToString("\n") { (case, result) ->
                    "- ${case.spec.id}: " + result.failures.joinToString("; ") {
                        "turn${it.turnIndex} ${it.kind} exp=${it.expected} act=${it.actual}"
                    }
                },
            gates.all { it.passed } && zeroGates.all { it.second == 0 },
        )
    }

    private data class Gate(val name: String, val hits: Int, val total: Int, val floor: Double) {
        val rate: Double get() = if (total == 0) 1.0 else hits.toDouble() / total
        val passed: Boolean get() = rate >= floor
        fun json(): String =
            "    {${EvalReport.q("gate")}: ${EvalReport.q(name)}, " +
                "${EvalReport.q("numerator")}: $hits, ${EvalReport.q("denominator")}: $total, " +
                "${EvalReport.q("rate")}: ${"%.4f".format(rate)}, " +
                "${EvalReport.q("floor")}: $floor, ${EvalReport.q("passed")}: $passed}"
    }
}
