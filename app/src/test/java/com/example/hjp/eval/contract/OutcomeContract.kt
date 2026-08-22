package com.example.hjp.eval.contract

import com.hjp.agent.contract.TurnOutcomeType

/**
 * Which version of the typed-outcome contract a dataset was written against.
 *
 * The two exist because production changed and frozen data cannot. A turn that stops because a
 * required slot is missing is a clarification: it ran no tool, produced no draft, and its reply asks
 * for the thing it lacks. v1–v3 nonetheless froze `GENERAL_INFORMATION` as the expected outcome for
 * exactly those turns, which made "asked the user for the missing time" and "gave a general reply
 * and did nothing" the same recorded value.
 *
 * Rather than edit frozen datasets (forbidden, and dishonest) or keep the app on the weaker
 * contract (a real defect), the app implements [V4] and the older datasets are *read through*
 * [LEGACY_V1_V3]. The translation is narrow, one-directional, and every use of it is recorded.
 */
enum class EvaluationContractVersion {
    /**
     * How v1, v2 and v3 expect outcomes to be typed.
     *
     * Preserved so their official results stay interpretable and reproducible. It is not a
     * description of correct behaviour and the app is never made to match it.
     */
    LEGACY_V1_V3,

    /** The contract the app implements from v4 onwards. Exact match, no translation. */
    V4,
}

/**
 * Compares an observed typed outcome against what a dataset expects.
 *
 * There is deliberately no substitution here any more. The first version of this object let an
 * observed `CLARIFICATION_REQUIRED` satisfy an expected `GENERAL_INFORMATION` whenever the turn ran
 * no tool, which sounded narrow and was not: general-knowledge questions, greetings, thanks and
 * unsupported requests all run no tool, so a regression that started asking "who do you mean?"
 * instead of answering would have passed. An audit put the real missing-slot population at 22 turns
 * against 55 accepted by that rule.
 *
 * Reconciling a frozen dataset with the newer contract is now a *projection*, decided before the run
 * from the dataset's own metadata — see [SemanticOutcomeOverlay]. By the time a comparison happens
 * there is one expected value and it must match exactly.
 */
object OutcomeContract {

    fun matches(
        version: EvaluationContractVersion,
        expected: TurnOutcomeType,
        observed: TurnOutcomeType?,
        executedTools: List<String>,
    ): Boolean {
        // Parameters kept so every call site still states which contract it is scoring under and
        // what the turn ran; neither may influence whether two outcomes are the same value.
        @Suppress("UNUSED_EXPRESSION") version
        @Suppress("UNUSED_EXPRESSION") executedTools
        return observed == expected
    }

    /**
     * Outcomes that assert the action happened. A turn missing a required slot may never carry one,
     * under either contract.
     */
    val SUCCEEDED_ACTIONS: Set<TurnOutcomeType> = setOf(
        TurnOutcomeType.COMPOSE_OPENED,
        TurnOutcomeType.CALENDAR_OPENED,
        TurnOutcomeType.UPDATE_COMPLETED,
    )
}
