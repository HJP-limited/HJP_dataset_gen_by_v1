package com.example.hjp.eval

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.core.ContextBudget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-turn admission accounting for long sessions.
 *
 * The number that matters is `raw + next_request_reserve + output_reserve + safety_margin`, not raw
 * alone. Recording every term per turn is what makes an over-budget claim impossible to state by
 * accident: the report shows the arithmetic, so "2,757 ≤ 3,072" cannot stand in for a decision that
 * actually needs 3,525.
 *
 * Token counts are an estimate produced by the app-side calibrated estimator reproducing the native
 * input. They are not readings of LiteRT's own tokenizer and must never be reported as such.
 */
class ContextStressTest {
    private val budget = ContextBudget(maxPromptTokens = 3_072)

    @Test
    fun `every turn of a long session stays admissible with reserves included`() = runBlocking {
        val runs = listOf(12, 20, 40).map { turns -> stress(turns) }
        EvalReport.write("context_stress.json", json(runs))

        val violations = runs.flatMap { run -> run.turns.filterNot { it.admitted } }
        assertTrue(
            "admission violations: " + violations.joinToString {
                "turn ${it.index} required=${it.requiredTotalAtSend} > budget=${it.budget}"
            },
            violations.isEmpty(),
        )
        // A 40-turn session must actually exercise rotation, or the check proves nothing.
        assertTrue("40-turn run never rotated", runs.last().rotations >= 1)
    }

    @Test
    fun `admission is decided at the boundary by the reserves, not by raw tokens alone`() {
        val reserves = budget.nextTurnReserveTokens + budget.reservedForResponseTokens +
            budget.safetyMarginTokens
        val exact = budget.admit(budget.maxPromptTokens - reserves)
        val overByOne = budget.admit(budget.maxPromptTokens - reserves + 1)
        val underBudgetRawOnly = budget.admit(budget.maxPromptTokens - 1)

        assertTrue("exactly at the limit must be admitted", exact.admitted)
        assertTrue("one token over must be refused", !overByOne.admitted)
        // Raw alone is under 3,072 here, yet the request cannot be served.
        assertTrue("raw-only comparison must not admit", !underBudgetRawOnly.admitted)
    }

    private suspend fun stress(turnCount: Int): StressRun {
        val harness = MultiturnScenarioHarness()
        val rows = mutableListOf<TurnRow>()
        repeat(turnCount) { index ->
            val before = harness.nativeLedger.snapshot()
            harness.turn("메모 $index 확인만 해줘.")
            val after = harness.nativeLedger.snapshot()
            // The request the runtime was actually asked to prefill, after any rotation the kernel
            // decided to perform. This is the number the budget has to hold.
            val atSend = harness.gateway.nativeTokensAtSend.lastOrNull() ?: after.totalTokens
            val requiredAtSend = atSend + budget.reservedForResponseTokens + budget.safetyMarginTokens
            // The accumulated total the *next* turn will be judged on, reserves included.
            val carryOver = budget.admit(after.totalTokens)
            rows += TurnRow(
                index = index + 1,
                rawBefore = before.totalTokens,
                rawAtSend = atSend,
                rawAfterTurn = after.totalTokens,
                nextReserve = carryOver.nextRequestReserveTokens,
                outputReserve = budget.reservedForResponseTokens,
                safetyMargin = budget.safetyMarginTokens,
                requiredTotalAtSend = requiredAtSend,
                carryOverRequiredTotal = carryOver.requiredTotalTokens,
                carryOverAdmitted = carryOver.admitted,
                budget = budget.maxPromptTokens,
                admitted = requiredAtSend <= budget.maxPromptTokens,
                rotationsSoFar = after.rotations,
                rotatedThisTurn = after.rotations > before.rotations,
            )
        }
        val snapshot = harness.nativeLedger.snapshot()
        harness.close()
        return StressRun(
            turnCount = turnCount,
            turns = rows,
            rotations = snapshot.rotations,
            maxRaw = rows.maxOf { it.rawAtSend },
            maxRequiredTotal = rows.maxOf { it.requiredTotalAtSend },
        )
    }

    private data class TurnRow(
        val index: Int,
        val rawBefore: Int,
        val rawAtSend: Int,
        val rawAfterTurn: Int,
        val nextReserve: Int,
        val outputReserve: Int,
        val safetyMargin: Int,
        val requiredTotalAtSend: Int,
        val carryOverRequiredTotal: Int,
        val carryOverAdmitted: Boolean,
        val budget: Int,
        val admitted: Boolean,
        val rotationsSoFar: Int,
        val rotatedThisTurn: Boolean,
    )

    private data class StressRun(
        val turnCount: Int,
        val turns: List<TurnRow>,
        val rotations: Int,
        val maxRaw: Int,
        val maxRequiredTotal: Int,
    )

    private fun json(runs: List<StressRun>): String = buildString {
        append("{\n")
        append("  \"formula_at_send\": ")
        append(EvalReport.q("raw_native_tokens + next_request_reserve + output_reserve + safety_margin <= model_context_budget"))
        append(",\n")
        append("  \"tokenizer_identity\": ")
        append(EvalReport.q("CalibratedGemmaTokenEstimator (app-side two-class estimator)"))
        append(",\n")
        append("  \"measurement_type\": ")
        append(EvalReport.q("estimated reproduction of the native input; NOT a LiteRT tokenizer reading"))
        append(",\n")
        append("  \"budget_tokens\": 3072,\n")
        append("  \"reserves\": {\"next_request\": ").append(budget.nextTurnReserveTokens)
        append(", \"output\": ").append(budget.reservedForResponseTokens)
        append(", \"safety_margin\": ").append(budget.safetyMarginTokens).append("},\n")
        append("  \"runs\": [\n")
        append(runs.joinToString(",\n") { run ->
            buildString {
                append("    {\"turn_count\": ").append(run.turnCount)
                append(", \"rotations\": ").append(run.rotations)
                append(", \"max_raw_tokens\": ").append(run.maxRaw)
                append(", \"max_required_total_tokens\": ").append(run.maxRequiredTotal)
                append(", \"admission_violations\": ").append(run.turns.count { !it.admitted })
                append(", \"turns_needing_rotation_next\": ").append(run.turns.count { !it.carryOverAdmitted })
                append(", \"turns\": [")
                append(run.turns.joinToString(", ") { row ->
                    "{\"turn\": ${row.index}, \"raw_before_turn\": ${row.rawBefore}, " +
                        "\"raw_native_tokens_at_send\": ${row.rawAtSend}, " +
                        "\"raw_native_tokens_after_turn\": ${row.rawAfterTurn}, " +
                        "\"next_request_reserve\": ${row.nextReserve}, " +
                        "\"output_reserve\": ${row.outputReserve}, " +
                        "\"safety_margin\": ${row.safetyMargin}, " +
                        "\"required_total_at_send\": ${row.requiredTotalAtSend}, " +
                        "\"carry_over_required_total\": ${row.carryOverRequiredTotal}, " +
                        "\"carry_over_admitted\": ${row.carryOverAdmitted}, " +
                        "\"budget\": ${row.budget}, " +
                        "\"admitted\": ${row.admitted}, " +
                        "\"rotated_this_turn\": ${row.rotatedThisTurn}, " +
                        "\"rotations_so_far\": ${row.rotationsSoFar}}"
                })
                append("]}")
            }
        })
        append("\n  ]\n}\n")
    }
}
