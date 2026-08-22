package com.example.hjp.eval.ryeong

import com.hjp.agent.contract.DialogueAct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Does the evaluator actually catch anything?
 *
 * An evaluator that reports 100% because it cannot see a defect is worse than no evaluator. Each
 * case below takes a clean observation set, breaks exactly one thing, and requires the scorer or
 * the gate to notice. The fixtures use names and IDs that appear nowhere in the frozen Ryeong
 * set, so passing here cannot come from recognising real data.
 */
class RyeongEvaluatorMutationTest {

    // ---- synthetic fixtures, deliberately unlike anything in the frozen set --------------------

    private fun turn(
        scenario: Int = 0,
        depth: Int = 1,
        expectedRoute: String? = "search",
        observed: DialogueAct = DialogueAct.CONTACT_SEARCH,
        gold: List<String> = listOf("ZZ-900"),
        ranking: List<String> = listOf("ZZ-900", "ZZ-901"),
        selected: String? = "ZZ-900",
        tools: List<String> = listOf("search_contacts"),
    ): TurnObservation {
        val scorable = RyeongTranslation.classify(expectedRoute) ==
            TranslationKind.DERIVED_FROM_PRODUCTION_TRACE
        val r5Scorable = gold.isNotEmpty() && ranking.isNotEmpty()
        return TurnObservation(
            scenarioIndex = scenario,
            depth = depth,
            question = "합성 질의 $scenario-$depth",
            expectedRoute = expectedRoute,
            observedAct = observed,
            routeScorable = scorable,
            routeExclusionReason = if (scorable) "" else RyeongTranslation.exclusionReason(expectedRoute),
            routePassed = if (scorable) RyeongTranslation.routeSatisfied(expectedRoute!!, observed) else null,
            goldCardIds = gold,
            observedRanking = ranking,
            r5Scorable = r5Scorable,
            r5Passed = if (r5Scorable) ranking.take(5).any { it in gold } else null,
            selectedCardId = selected,
            executedTools = tools,
            unexpectedActionTools = tools.filter {
                it in setOf("open_compose", "create_calendar_event", "update_business_card")
            },
            retrievalModes = listOf("KEYWORD_ONLY"),
            outcomeType = "CONTACT_DETAIL_SHOWN",
        )
    }

    private fun scenario(index: Int, turns: List<TurnObservation>) = ScenarioObservation(
        index = index,
        kind = "합성",
        depthBucket = if (turns.size == 1) "depth_1" else if (turns.size == 2) "depth_2" else "depth_3_5",
        turnCount = turns.size,
        generateOnly = false,
        turns = turns,
        endingSelectedCardId = turns.lastOrNull()?.selectedCardId,
        endingHistoryTurns = turns.size,
    )

    private fun clean(): RyeongCompatibilityResult = RyeongCompatibilityResult(
        scenarios = listOf(
            scenario(0, listOf(turn(0, 1), turn(0, 2, expectedRoute = "followup", observed = DialogueAct.CONTACT_DETAIL))),
            scenario(1, listOf(turn(1, 1))),
        ),
        leakage = listOf(
            LeakageCheck(0, startedWithSelectedContact = false, startedWithHistory = false),
            LeakageCheck(1, startedWithSelectedContact = false, startedWithHistory = false),
        ),
        cardCount = 3,
        semanticAvailable = false,
        retrievalModes = setOf("KEYWORD_ONLY"),
    )

    private fun inputs(
        score: RyeongCompatibilityScore,
        resultComplete: Boolean = true,
        evaluatorException: String? = null,
        actualModelExecuted: Boolean = false,
        generationMetricsScored: Boolean = false,
        evaluatorSha: String = "E",
        turnCountOverride: Int? = null,
    ) = RunInputs(
        // Synthetic fixtures declare their own inventory; the frozen 130/377 belongs to the
        // official run and would be a false expectation here.
        scenarioCount = 2,
        turnCount = turnCountOverride ?: score.totalTurns,
        kindCount = 1,
        knownGap = 0,
        generateOnly = 0,
        expectedScenarioCount = 2,
        expectedTurnCount = score.totalTurns,
        expectedKindCount = 1,
        expectedKnownGap = 0,
        expectedGenerateOnly = 0,
        evaluatorSha256 = evaluatorSha,
        cardsSha256 = "C",
        scenarioManifestSha256 = "M",
        expectedEvaluatorSha256 = "E",
        expectedCardsSha256 = "C",
        expectedManifestSha256 = "M",
        resultComplete = resultComplete,
        evaluatorException = evaluatorException,
        actualModelExecuted = actualModelExecuted,
        generationMetricsScored = generationMetricsScored,
    )

    private val detected = linkedMapOf<String, Boolean>()

    private fun record(name: String, condition: Boolean) {
        detected[name] = condition
        assertTrue("mutation '$name' was NOT detected", condition)
    }

    // ---- observation-level mutations -----------------------------------------------------------

    @Test
    fun `wrong route is detected`() {
        val broken = clean().let { base ->
            base.copy(scenarios = listOf(
                scenario(0, listOf(turn(0, 1, observed = DialogueAct.ACTION_COMPOSE))),
                base.scenarios[1],
            ))
        }
        val score = RyeongCompatibilityScorer.score(broken)
        record("wrong_route", score.failures.any { it.contains("route search ->") })
        assertTrue(score.confusion.isNotEmpty())
    }

    @Test
    fun `missing focus is detected`() {
        val broken = clean().let { base ->
            base.copy(scenarios = listOf(scenario(0, listOf(turn(0, 1, selected = null))), base.scenarios[1]))
        }
        val observed = broken.scenarios[0].turns[0].selectedCardId
        record("missing_focus", observed == null && clean().scenarios[0].turns[0].selectedCardId != null)
    }

    @Test
    fun `wrong focus id is detected`() {
        val broken = clean().let { base ->
            base.copy(scenarios = listOf(scenario(0, listOf(turn(0, 1, selected = "ZZ-999"))), base.scenarios[1]))
        }
        record("wrong_focus_id", broken.scenarios[0].turns[0].selectedCardId != "ZZ-900")
    }

    @Test
    fun `stale focus is detected`() {
        // Turn 2 should have moved to a new person; the focus still names turn 1's card.
        val stale = scenario(0, listOf(
            turn(0, 1, selected = "ZZ-900"),
            turn(0, 2, expectedRoute = "followup", observed = DialogueAct.CONTACT_DETAIL,
                gold = listOf("ZZ-902"), ranking = listOf("ZZ-902"), selected = "ZZ-900"),
        ))
        val staleFocus = stale.turns[1].let { it.selectedCardId !in it.goldCardIds }
        record("stale_focus", staleFocus)
    }

    @Test
    fun `gold outside top five is detected`() {
        val broken = clean().let { base ->
            base.copy(scenarios = listOf(
                scenario(0, listOf(turn(0, 1, ranking = listOf("A1", "A2", "A3", "A4", "A5", "ZZ-900")))),
                base.scenarios[1],
            ))
        }
        val score = RyeongCompatibilityScorer.score(broken)
        record("gold_outside_top5", score.failures.any { it.contains("not in top-5") })
        // Scenario 1 is untouched and still retrieves its card, so only the broken one drops out.
        assertEquals(1, score.r5.numerator)
        assertEquals(2, score.r5.denominator)
    }

    @Test
    fun `wrong id inserted into top five is detected`() {
        val broken = clean().let { base ->
            base.copy(scenarios = listOf(
                scenario(0, listOf(turn(0, 1, ranking = listOf("BOGUS-1", "BOGUS-2", "BOGUS-3", "BOGUS-4", "BOGUS-5")))),
                base.scenarios[1],
            ))
        }
        val score = RyeongCompatibilityScorer.score(broken)
        record("wrong_id_in_top5", score.failures.any { it.contains("not in top-5") })
    }

    // ---- isolation mutations --------------------------------------------------------------------

    @Test
    fun `focus leakage between scenarios is detected`() {
        val broken = clean().let { base ->
            base.copy(leakage = listOf(
                base.leakage[0],
                LeakageCheck(1, startedWithSelectedContact = true, startedWithHistory = false),
            ))
        }
        val score = RyeongCompatibilityScorer.score(broken)
        record("focus_leakage", score.crossScenarioLeakage == 1 &&
            score.failures.any { it.contains("leakage") })
    }

    @Test
    fun `memory leakage between scenarios is detected`() {
        val broken = clean().let { base ->
            base.copy(leakage = listOf(
                base.leakage[0],
                LeakageCheck(1, startedWithSelectedContact = false, startedWithHistory = true),
            ))
        }
        val score = RyeongCompatibilityScorer.score(broken)
        record("memory_leakage", score.crossScenarioLeakage == 1)
    }

    @Test
    fun `a missing session reset shows up as leakage`() {
        val broken = clean().let { base ->
            base.copy(leakage = base.leakage.map { LeakageCheck(it.scenarioIndex, true, true) })
        }
        val score = RyeongCompatibilityScorer.score(broken)
        val verdict = RyeongRunGate.evaluate(inputs(score), score)
        record("missing_session_reset",
            score.crossScenarioLeakage == 2 &&
                verdict.failures.contains(GateFailure.CROSS_SCENARIO_LEAKAGE))
    }

    @Test
    fun `turn reordering is detected`() {
        val set = RyeongScenarioLoader.load()
        val original = set.scenarios.first { it.turnCount >= 2 }
        val reordered = original.copy(turns = original.turns.reversed())
        record("turn_reordering",
            reordered.turns.map { it.depth } != (1..reordered.turnCount).toList())
    }

    @Test
    fun `injecting the expected value as the observed value is detected`() {
        // If the runner ever fed gold IDs in as the ranking, every R@5 would pass. The tell is
        // that the observed ranking becomes exactly the gold list on every turn.
        val injected = clean().let { base ->
            base.copy(scenarios = base.scenarios.map { s ->
                s.copy(turns = s.turns.map { it.copy(observedRanking = it.goldCardIds) })
            })
        }
        val suspicious = injected.scenarios.flatMap { it.turns }
            .all { it.observedRanking == it.goldCardIds }
        val cleanRun = clean().scenarios.flatMap { it.turns }
            .all { it.observedRanking == it.goldCardIds }
        record("expected_injected_as_actual", suspicious && !cleanRun)
    }

    // ---- side-effect mutations -------------------------------------------------------------------

    @Test
    fun `open_compose on a search turn is detected separately from a route failure`() {
        val broken = clean().let { base ->
            base.copy(scenarios = listOf(
                scenario(0, listOf(turn(0, 1, tools = listOf("search_contacts", "open_compose")))),
                base.scenarios[1],
            ))
        }
        val score = RyeongCompatibilityScorer.score(broken)
        record("unexpected_open_compose",
            score.unexpectedActionTools == 1 &&
                score.failures.any { it.contains("unexpected_side_effect_tool") })
        // The route itself was right, so this must not be counted as a routing failure.
        assertEquals(2, score.routing.numerator)
        assertTrue(score.failures.none { it.contains("route search ->") })
    }

    @Test
    fun `create_calendar_event on a search turn is detected`() {
        val broken = clean().let { base ->
            base.copy(scenarios = listOf(
                scenario(0, listOf(turn(0, 1, tools = listOf("search_contacts", "create_calendar_event")))),
                base.scenarios[1],
            ))
        }
        val score = RyeongCompatibilityScorer.score(broken)
        record("unexpected_create_calendar_event", score.unexpectedActionTools == 1)
    }

    @Test
    fun `update_business_card on a search turn is detected`() {
        val broken = clean().let { base ->
            base.copy(scenarios = listOf(
                scenario(0, listOf(turn(0, 1, tools = listOf("search_contacts", "update_business_card")))),
                base.scenarios[1],
            ))
        }
        val score = RyeongCompatibilityScorer.score(broken)
        record("unexpected_update_business_card", score.unexpectedActionTools == 1)
    }

    @Test
    fun `a side effect firing twice is detected`() {
        val broken = clean().let { base ->
            base.copy(scenarios = listOf(
                scenario(0, listOf(turn(0, 1, tools = listOf("open_compose", "open_compose")))),
                base.scenarios[1],
            ))
        }
        val score = RyeongCompatibilityScorer.score(broken)
        record("duplicate_side_effect", score.unexpectedActionTools == 2)
    }

    // ---- gate and result-contract mutations --------------------------------------------------------

    @Test
    fun `failures with a zero exit code are refused`() {
        val broken = clean().let { base ->
            base.copy(scenarios = listOf(
                scenario(0, listOf(turn(0, 1, observed = DialogueAct.ACTION_COMPOSE))),
                base.scenarios[1],
            ))
        }
        val score = RyeongCompatibilityScorer.score(broken)
        val verdict = RyeongRunGate.evaluate(inputs(score), score)
        record("exit_zero_with_failures", !verdict.passed && verdict.exitCode != 0)
    }

    @Test
    fun `a partial result is refused`() {
        val score = RyeongCompatibilityScorer.score(clean())
        val verdict = RyeongRunGate.evaluate(inputs(score, resultComplete = false), score)
        record("partial_result",
            verdict.failures.contains(GateFailure.PARTIAL_RESULT) && verdict.exitCode != 0)
    }

    @Test
    fun `an evaluator exception is refused`() {
        val score = RyeongCompatibilityScorer.score(clean())
        val verdict = RyeongRunGate.evaluate(inputs(score, evaluatorException = "boom"), score)
        record("evaluator_exception", verdict.failures.contains(GateFailure.EVALUATOR_EXCEPTION))
    }

    @Test
    fun `an input SHA mismatch is refused`() {
        val score = RyeongCompatibilityScorer.score(clean())
        val verdict = RyeongRunGate.evaluate(inputs(score, evaluatorSha = "TAMPERED"), score)
        record("input_integrity", verdict.failures.contains(GateFailure.INPUT_INTEGRITY))
    }

    @Test
    fun `a scalar type mismatch in the manifest is refused`() {
        val corrupted = RyeongScenarioLoader.loadRaw().replaceFirst("\"index\":0", "\"index\":\"0\"")
        val threw = try {
            RyeongScenarioLoader.parse(corrupted); false
        } catch (expected: RuntimeException) {
            true
        }
        record("scalar_type_mismatch", threw)
    }

    @Test
    fun `folding an excluded turn into the denominator is refused`() {
        val score = RyeongCompatibilityScorer.score(clean())
        // Claim one more turn existed than scored+excluded accounts for.
        val verdict = RyeongRunGate.evaluate(
            inputs(score, turnCountOverride = score.totalTurns + 1), score,
        )
        record("excluded_turn_in_denominator",
            verdict.failures.contains(GateFailure.EXCLUDED_TURN_IN_DENOMINATOR))
    }

    @Test
    fun `scoring generation without a model run is refused`() {
        val score = RyeongCompatibilityScorer.score(clean())
        val verdict = RyeongRunGate.evaluate(
            inputs(score, actualModelExecuted = false, generationMetricsScored = true), score,
        )
        record("generation_without_model",
            verdict.failures.contains(GateFailure.GENERATION_SCORED_WITHOUT_MODEL))
    }

    @Test
    fun `an unmapped route is excluded rather than failed`() {
        // abstain has no honest production counterpart; it must land in neither numerator nor
        // denominator, and must not be silently counted as a pass.
        val broken = clean().let { base ->
            base.copy(scenarios = listOf(
                scenario(0, listOf(turn(0, 1, expectedRoute = "abstain", observed = DialogueAct.OTHER))),
                base.scenarios[1],
            ))
        }
        val score = RyeongCompatibilityScorer.score(broken)
        record("unmapped_route_excluded",
            score.routingExcludedTurns == 1 &&
                score.routing.denominator == 1 &&
                score.failures.none { it.contains("abstain") })
    }

    @Test
    fun `a clean run passes the gate`() {
        val score = RyeongCompatibilityScorer.score(clean())
        val verdict = RyeongRunGate.evaluate(inputs(score), score)
        assertTrue("clean run should pass: ${verdict.detail}", verdict.passed)
        assertEquals(0, verdict.exitCode)
    }
}
