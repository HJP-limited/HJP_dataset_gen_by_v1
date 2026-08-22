package com.example.hjp.eval.ryeong

/** Why a run was refused. Ordered so the most fundamental problem is reported first. */
enum class GateFailure {
    INPUT_INTEGRITY,
    EXACT_INVENTORY,
    EVALUATOR_EXCEPTION,
    PARTIAL_RESULT,
    CROSS_SCENARIO_LEAKAGE,
    UNEXPECTED_SIDE_EFFECT_TOOL,
    EXCLUDED_TURN_IN_DENOMINATOR,
    GENERATION_SCORED_WITHOUT_MODEL,
    SCORED_FAILURES_PRESENT,
}

data class GateVerdict(
    val failures: List<GateFailure>,
    val detail: Map<GateFailure, String>,
) {
    val passed: Boolean get() = failures.isEmpty()

    /**
     * The exit code contract, stated once.
     *
     * Upstream returns 0 no matter how many scenarios failed. That behaviour is deliberately not
     * carried over: anything that went wrong produces a non-zero code, so a green exit means green.
     */
    val exitCode: Int get() = if (passed) 0 else 1
}

data class RunInputs(
    val scenarioCount: Int,
    val turnCount: Int,
    val kindCount: Int,
    val knownGap: Int,
    val generateOnly: Int,
    val evaluatorSha256: String,
    val cardsSha256: String,
    val scenarioManifestSha256: String,
    val expectedEvaluatorSha256: String,
    val expectedCardsSha256: String,
    val expectedManifestSha256: String,
    val resultComplete: Boolean,
    val evaluatorException: String? = null,
    val actualModelExecuted: Boolean = false,
    val generationMetricsScored: Boolean = false,
    /**
     * The inventory this run is required to have.
     *
     * Defaults to the frozen Ryeong figures. A canary or self-test run declares its own, so the
     * gate checks "is this the set you said you were running" rather than "is this the frozen
     * set" — the frozen numbers stay a hard requirement for the official run alone.
     */
    val expectedScenarioCount: Int = RyeongScenarioLoader.EXPECTED_SCENARIOS,
    val expectedTurnCount: Int = RyeongScenarioLoader.EXPECTED_TURNS,
    val expectedKindCount: Int = RyeongScenarioLoader.EXPECTED_KINDS,
    val expectedKnownGap: Int = RyeongScenarioLoader.EXPECTED_KNOWN_GAP,
    val expectedGenerateOnly: Int = RyeongScenarioLoader.EXPECTED_GENERATE_ONLY,
)

/**
 * Decides whether a run may be called a pass.
 *
 * Every check is a refusal condition, not a warning. In particular a scored failure is a gate
 * failure: a run that produced numbers but also produced failures is not a green run.
 */
object RyeongRunGate {

    fun evaluate(inputs: RunInputs, score: RyeongCompatibilityScore): GateVerdict {
        val detail = linkedMapOf<GateFailure, String>()

        if (inputs.evaluatorSha256 != inputs.expectedEvaluatorSha256 ||
            inputs.cardsSha256 != inputs.expectedCardsSha256 ||
            inputs.scenarioManifestSha256 != inputs.expectedManifestSha256
        ) {
            detail[GateFailure.INPUT_INTEGRITY] =
                "one of evaluator/cards/manifest SHA-256 does not match the freeze"
        }

        if (inputs.scenarioCount != inputs.expectedScenarioCount ||
            inputs.turnCount != inputs.expectedTurnCount ||
            inputs.kindCount != inputs.expectedKindCount ||
            inputs.knownGap != inputs.expectedKnownGap ||
            inputs.generateOnly != inputs.expectedGenerateOnly
        ) {
            detail[GateFailure.EXACT_INVENTORY] =
                "inventory ${inputs.scenarioCount}/${inputs.turnCount}/${inputs.kindCount} " +
                    "gap=${inputs.knownGap} genOnly=${inputs.generateOnly} does not match the freeze"
        }

        inputs.evaluatorException?.let { detail[GateFailure.EVALUATOR_EXCEPTION] = it }

        if (!inputs.resultComplete) {
            detail[GateFailure.PARTIAL_RESULT] = "only a partial result was written"
        }

        if (score.crossScenarioLeakage > 0) {
            detail[GateFailure.CROSS_SCENARIO_LEAKAGE] =
                "${score.crossScenarioLeakage} scenario(s) started with state from a previous one"
        }

        if (score.unexpectedActionTools > 0) {
            detail[GateFailure.UNEXPECTED_SIDE_EFFECT_TOOL] =
                "${score.unexpectedActionTools} action tool call(s) on search-only turns"
        }

        // The routing denominator must be exactly the scorable turns. If an excluded turn were
        // folded in, the rate would improve or degrade for a reason that has nothing to do with
        // the agent.
        if (score.routing.denominator + score.routingExcludedTurns != inputs.turnCount) {
            detail[GateFailure.EXCLUDED_TURN_IN_DENOMINATOR] =
                "scored ${score.routing.denominator} + excluded ${score.routingExcludedTurns} " +
                    "!= ${inputs.turnCount} turns"
        }

        if (inputs.generationMetricsScored && !inputs.actualModelExecuted) {
            detail[GateFailure.GENERATION_SCORED_WITHOUT_MODEL] =
                "generation metrics were scored without an actual model run"
        }

        if (score.failures.isNotEmpty()) {
            detail[GateFailure.SCORED_FAILURES_PRESENT] = "${score.failures.size} scored failure(s)"
        }

        return GateVerdict(detail.keys.toList(), detail)
    }
}
