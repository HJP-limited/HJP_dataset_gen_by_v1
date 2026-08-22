package com.example.hjp.eval

import com.example.hjp.eval.contract.EvaluationContractVersion
import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord

/**
 * One turn of an evaluation scenario, with everything the turn is allowed to do.
 *
 * Every field is an assertion, not a hint. [expectedAct] and [expectedOutcome] are mandatory: a case
 * that declares neither cannot distinguish "the agent did the right thing" from "the agent did
 * nothing", which is exactly how the legacy suite scored capability blurbs as passes.
 */
data class TurnSpec(
    val user: String,
    val expectedAct: DialogueAct? = null,
    val expectedOutcome: TurnOutcomeType? = null,
    /** Exact tool trace for this turn, in order. */
    val expectedTools: List<String> = emptyList(),
    /** Tools that must not run in this turn even if [expectedTools] is not exhaustive. */
    val forbiddenTools: Set<String> = emptySet(),
    /** tool name -> argument key -> required value. */
    val expectedArgs: Map<String, Map<String, String>> = emptyMap(),
    val expectedSelectedCardId: String? = null,
    val expectNoSelectedContact: Boolean = false,
    val expectedSelectedNotCardId: String? = null,
    val expectedCandidateIds: List<String>? = null,
    /** compose + calendar screens this turn may open. */
    val expectedSideEffects: Int = 0,
    val expectedComposeTo: String? = null,
    val answerMustContain: List<String> = emptyList(),
    val answerMustNotContain: List<String> = emptyList(),
    /** `새 대화` is issued immediately before this turn. */
    val resetBefore: Boolean = false,
)

/**
 * One scenario. [primaryCategory] is single-valued on purpose so a case counts once in the
 * denominator; [secondaryTags] carry everything else it also exercises.
 */
data class MultiturnSpec(
    val id: String,
    val primaryCategory: String,
    val secondaryTags: List<String> = emptyList(),
    val turns: List<TurnSpec>,
    val cards: List<BusinessCardRecord> = MultiturnScenarioHarness.DEFAULT_CARDS,
    val searchFailure: Boolean = false,
    val confirmUpdates: Boolean = true,
    /** Personal data the agent must never have used in this scenario. */
    val forbiddenValues: List<String> = emptyList(),
    /** Which dataset this case belongs to. Only used to label migration records. */
    val datasetVersion: String = "visible",
    /**
     * Which typed-outcome contract this case's expectations were written against.
     *
     * Defaults to the current one. The frozen v1 held-out set declares
     * [EvaluationContractVersion.LEGACY_V1_V3] instead, so its expectations stay readable without
     * the app being held to them.
     */
    val contractVersion: EvaluationContractVersion = EvaluationContractVersion.V4,
) {
    val userTurnCount: Int get() = turns.size

    /** Shape of the scenario, ignoring which people and values it happens to use. */
    fun templateSignature(): String = turns.joinToString("|") { turn ->
        buildString {
            append(turn.expectedAct?.name ?: "?")
            append('>')
            append(turn.expectedOutcome?.name ?: "?")
            append('>')
            append(turn.expectedTools.joinToString("+"))
            if (turn.resetBefore) append("|RESET")
        }
    }
}

object EvalCategories {
    const val REFERENCE = "reference_resolution"
    const val SLOT = "slot_and_correction"
    const val TOOLS = "tool_and_workflow"
    const val BOUNDARY = "action_vs_information"
    const val AMBIGUITY = "ambiguity_and_grounding"
    const val FAILURE = "failure_and_idempotency"
    const val SAFETY = "unsupported_and_adversarial"
    const val CONSISTENCY = "result_response_consistency"

    /** Floor for each primary category in the visible suite. */
    val MINIMUMS = linkedMapOf(
        REFERENCE to 36,
        SLOT to 36,
        TOOLS to 36,
        BOUNDARY to 36,
        AMBIGUITY to 28,
        FAILURE to 24,
        SAFETY to 12,
        CONSISTENCY to 12,
    )
}
