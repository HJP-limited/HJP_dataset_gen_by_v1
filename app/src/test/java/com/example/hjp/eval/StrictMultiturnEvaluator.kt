package com.example.hjp.eval

import com.example.hjp.eval.contract.SemanticOutcomeOverlay
import com.example.hjp.eval.contract.LegacyOutcomeMigrationLog
import com.example.hjp.eval.contract.OutcomeContract
import com.example.hjp.eval.contract.EvaluationContractVersion
import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.TurnOutcomeType
import kotlinx.serialization.json.JsonPrimitive

/** One failed expectation, with both sides of the comparison kept for the report. */
data class AssertionFailure(
    val turnIndex: Int,
    val kind: String,
    val expected: String,
    val actual: String,
    val reason: String,
    /** Strict-only failures do not sink task success. */
    val strictOnly: Boolean = false,
)

data class TurnObservation(
    val user: String,
    val act: String,
    val outcome: String?,
    val tools: List<String>,
    val selectedCardId: String?,
    val candidateIds: List<String>,
    val sideEffects: Int,
    val answer: String,
)

data class ScenarioResult(
    val id: String,
    val primaryCategory: String,
    val secondaryTags: List<String>,
    val userTurnCount: Int,
    val turns: List<TurnObservation>,
    val failures: List<AssertionFailure>,
    val environment: Map<String, String>,
) {
    val taskSuccess: Boolean get() = failures.none { !it.strictOnly }
    val strictSuccess: Boolean get() = failures.isEmpty()

    /**
     * What the legacy evaluator would have concluded.
     *
     * Its whole vocabulary was about the compose screen: the right recipient when a case named one,
     * and no compose when a case said so. Everything else — the route, the typed outcome, the tool
     * trace, the final sentence — went unchecked, which is why eighteen of its forty-eight cases
     * asserted nothing but "no compose happened".
     */
    val legacyPass: Boolean
        get() = failures.none { failure ->
            failure.kind == "compose_recipient" ||
                (failure.kind == "forbidden_tool" && failure.actual.contains("open_compose"))
        }
}

/**
 * Runs a [MultiturnSpec] against the real kernel and scores it.
 *
 * The scoring rule that matters is the one the legacy evaluator lacked: a case with nothing to
 * assert fails. Everything else follows from that — a turn must declare what it is and what it
 * produced, and any tool or side effect outside the declaration is a failure rather than a silent
 * extra.
 */
object StrictMultiturnEvaluator {
    private val SIDE_EFFECT_TOOLS = setOf("open_compose", "create_calendar_event", "update_business_card")
    private val EMAIL = Regex("[A-Za-z0-9._-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    /** Text that claims an external action finished. Saying this after a failure is a false completion. */
    private val COMPLETION_CLAIMS = listOf(
        "열었습니다", "수정했습니다", "전송했습니다", "발송했습니다", "완료했습니다", "저장했습니다",
    )
    private val FAILURE_CLAIMS = listOf(
        "실패했습니다", "하지 못했습니다", "확인하지 못했습니다", "열지 못했습니다", "완료하지 못했습니다",
    )

    /**
     * Everything the assertions read about one turn.
     *
     * Separating the facts from the run is what lets a mutant inject a specific fault — a swapped
     * candidate, a reused address, a doubled side effect — without touching production code, so the
     * suite can prove the evaluator would actually catch each one.
     */
    data class TurnFacts(
        val act: com.hjp.agent.contract.DialogueAct,
        val outcome: TurnOutcomeType?,
        val executedTools: List<String>,
        val toolArguments: List<Pair<String, kotlinx.serialization.json.JsonObject>>,
        val selectedCardId: String?,
        val candidateIds: List<String>,
        val sideEffects: Int,
        val composeRecipients: List<String>,
        val answer: String,
    )

    fun interface TurnMutation {
        fun apply(turnIndex: Int, facts: TurnFacts): TurnFacts
    }

    val NO_MUTATION = TurnMutation { _, facts -> facts }

    suspend fun evaluate(
        spec: MultiturnSpec,
        mutation: TurnMutation = NO_MUTATION,
        /**
         * Which typed-outcome contract this dataset's expectations were written against.
         *
         * Defaults to whatever the case declares, which is the current contract. The frozen v1
         * held-out runner overrides it so that dataset stays readable without its file being edited.
         */
        contractVersion: EvaluationContractVersion = spec.contractVersion,
    ): ScenarioResult {
        val harness = MultiturnScenarioHarness(
            cards = spec.cards,
            searchFailure = spec.searchFailure,
            confirmUpdates = spec.confirmUpdates,
        )
        val failures = mutableListOf<AssertionFailure>()
        val observations = mutableListOf<TurnObservation>()

        if (spec.turns.isEmpty()) {
            failures += AssertionFailure(-1, "empty_scenario", "at least one turn", "none", "no turns")
        }

        spec.turns.forEachIndexed { index, turn ->
            if (turn.resetBefore) harness.reset()
            val record = harness.turn(turn.user)
            val observed = TurnFacts(
                act = record.act,
                outcome = record.outcomeType,
                executedTools = record.executedTools,
                toolArguments = record.toolArguments,
                selectedCardId = record.memory.selectedContact?.cardId,
                candidateIds = record.memory.candidateContacts.map { it.cardId },
                sideEffects = record.newComposeDrafts.size + record.newCalendarDrafts.size +
                    record.executedTools.count { it == "update_business_card" },
                composeRecipients = record.newComposeDrafts.map { it.to },
                answer = record.answer,
            )
            val facts = mutation.apply(index, observed)
            observations += TurnObservation(
                user = turn.user,
                act = facts.act.name,
                outcome = facts.outcome?.name,
                tools = facts.executedTools,
                selectedCardId = facts.selectedCardId,
                candidateIds = facts.candidateIds,
                sideEffects = facts.sideEffects,
                answer = facts.answer,
            )
            failures += checkTurn(spec, index, turn, facts, contractVersion)
        }

        failures += checkScenarioInvariants(spec, harness)
        harness.close()

        return ScenarioResult(
            id = spec.id,
            primaryCategory = spec.primaryCategory,
            secondaryTags = spec.secondaryTags,
            userTurnCount = spec.userTurnCount,
            turns = observations,
            failures = failures,
            environment = ENVIRONMENT,
        )
    }

    private fun checkTurn(
        spec: MultiturnSpec,
        index: Int,
        turn: TurnSpec,
        record: TurnFacts,
        contractVersion: EvaluationContractVersion,
    ): List<AssertionFailure> {
        val sideEffects = record.sideEffects
        val failures = mutableListOf<AssertionFailure>()

        // A turn that declares nothing cannot be evidence of anything.
        if (turn.expectedAct == null) {
            failures += AssertionFailure(
                index, "missing_expected_route", "a DialogueAct", "null",
                "case declares no expected route; it cannot distinguish correct routing from none",
            )
        }
        if (turn.expectedOutcome == null) {
            failures += AssertionFailure(
                index, "missing_expected_outcome", "a TurnOutcomeType", "null",
                "case declares no expected outcome",
            )
        }

        turn.expectedAct?.let {
            if (record.act != it) {
                failures += AssertionFailure(
                    index, "dialogue_act", it.name, record.act.name, "routed as a different act",
                )
            }
        }
        turn.expectedOutcome?.let { frozenExpected ->
            // v1 is the only legacy dataset here; the overlay projects only the turns its frozen
            // metadata marks as missing a required slot, and leaves everything else alone.
            val label = datasetLabel(contractVersion)
            val expectedOutcome =
                SemanticOutcomeOverlay.expectedOutcome(label, spec.id, index, frozenExpected)
            if (!OutcomeContract.matches(contractVersion, expectedOutcome, record.outcome, record.executedTools)) {
                failures += AssertionFailure(
                    index, "outcome_type", expectedOutcome.name, record.outcome?.name ?: "null",
                    "turn produced a different typed outcome",
                )
            }
            if (expectedOutcome != frozenExpected) {
                LegacyOutcomeMigrationLog.record(
                    label, spec.id, index, turn.user, spec.primaryCategory, frozenExpected, expectedOutcome,
                )
            }
        }
        if (record.executedTools != turn.expectedTools) {
            failures += AssertionFailure(
                index, "tool_trace", turn.expectedTools.toString(), record.executedTools.toString(),
                "tool trace differs from the declared trace",
            )
        }
        val forbidden = record.executedTools.filter { it in turn.forbiddenTools }
        if (forbidden.isNotEmpty()) {
            failures += AssertionFailure(
                index, "forbidden_tool", "none of ${turn.forbiddenTools}", forbidden.toString(),
                "a tool the case forbids was executed",
            )
        }
        turn.expectedArgs.forEach { (tool, args) ->
            val actual = record.toolArguments.lastOrNull { it.first == tool }?.second
            if (actual == null) {
                failures += AssertionFailure(
                    index, "tool_arguments", "$tool called", "not called",
                    "argument assertion declared for a tool that never ran",
                )
                return@forEach
            }
            args.forEach { (key, expected) ->
                val value = (actual[key] as? JsonPrimitive)?.content
                if (value != expected) {
                    failures += AssertionFailure(
                        index, "tool_argument:$tool.$key", expected, value ?: "null",
                        "argument value differs",
                    )
                }
            }
        }
        if (sideEffects != turn.expectedSideEffects) {
            failures += AssertionFailure(
                index, "side_effect_count", turn.expectedSideEffects.toString(), sideEffects.toString(),
                "number of irreversible actions differs",
            )
        }
        turn.expectedComposeTo?.let {
            val actual = record.composeRecipients.lastOrNull()
            if (actual != it) {
                failures += AssertionFailure(
                    index, "compose_recipient", it, actual ?: "none", "compose opened for the wrong target",
                )
            }
        }
        turn.expectedSelectedCardId?.let {
            val actual = record.selectedCardId
            if (actual != it) {
                failures += AssertionFailure(
                    index, "selected_contact", it, actual ?: "null", "wrong contact is in focus",
                )
            }
        }
        turn.expectedSelectedNotCardId?.let {
            if (record.selectedCardId == it) {
                failures += AssertionFailure(
                    index, "rejected_contact_still_selected", "not $it", it,
                    "a rejected target is still actionable",
                )
            }
        }
        if (turn.expectNoSelectedContact && record.selectedCardId != null) {
            failures += AssertionFailure(
                index, "selected_contact", "null", record.selectedCardId,
                "focus should have been cleared",
            )
        }
        turn.expectedCandidateIds?.let {
            val actual = record.candidateIds
            if (actual != it) {
                failures += AssertionFailure(
                    index, "candidates", it.toString(), actual.toString(), "candidate list differs",
                )
            }
        }
        turn.answerMustContain.forEach {
            if (!record.answer.contains(it)) {
                failures += AssertionFailure(
                    index, "answer_contains", it, record.answer.take(160),
                    "final response is missing required content", strictOnly = true,
                )
            }
        }
        turn.answerMustNotContain.forEach {
            if (record.answer.contains(it)) {
                failures += AssertionFailure(
                    index, "answer_excludes", "not '$it'", record.answer.take(160),
                    "final response contains forbidden content", strictOnly = true,
                )
            }
        }

        failures += checkConsistency(index, turn, record)
        return failures
    }

    /**
     * Tool reality against what the user was told.
     *
     * These two checks are the ones that make an internally correct trace worthless if the sentence
     * on screen contradicts it, in either direction.
     */
    /** v1 is the only legacy dataset reaching this evaluator; everything else is current. */
    private fun datasetLabel(version: EvaluationContractVersion): String =
        if (version == EvaluationContractVersion.LEGACY_V1_V3) "v1" else "visible"

    private fun checkConsistency(
        index: Int,
        turn: TurnSpec,
        record: TurnFacts,
    ): List<AssertionFailure> {
        val failures = mutableListOf<AssertionFailure>()
        val ranSideEffect = record.executedTools.any { it in SIDE_EFFECT_TOOLS }
        val claimsFailure = FAILURE_CLAIMS.any(record.answer::contains)
        val claimsCompletion = COMPLETION_CLAIMS.any(record.answer::contains)
        val failedOutcome = record.outcome == TurnOutcomeType.FAILED

        if (ranSideEffect && claimsFailure && !failedOutcome) {
            failures += AssertionFailure(
                index, "success_reported_as_failure", "no failure wording after a successful tool",
                record.answer.take(160), "a completed tool was reported to the user as a failure",
            )
        }
        if (failedOutcome && claimsCompletion) {
            failures += AssertionFailure(
                index, "false_completion", "no completion wording after a failure",
                record.answer.take(160), "a failed turn told the user the work was done",
            )
        }
        if (turn.expectedSideEffects == 0 && claimsCompletion && record.executedTools.isEmpty()) {
            failures += AssertionFailure(
                index, "false_completion", "no completion wording without any tool",
                record.answer.take(160), "completion claimed although no tool ran",
            )
        }
        return failures
    }

    private fun checkScenarioInvariants(
        spec: MultiturnSpec,
        harness: MultiturnScenarioHarness,
    ): List<AssertionFailure> {
        val failures = mutableListOf<AssertionFailure>()
        if (harness.messages.drafts.any { it.to.isBlank() }) {
            failures += AssertionFailure(-1, "blank_recipient", "non-blank", "blank", "composed to nobody")
        }
        // An address the user typed is theirs; an address only a tool result knew must never be
        // rendered back into a model prompt.
        val userProvided = spec.turns
            .flatMap { EMAIL.findAll(it.user).map(MatchResult::value) }
            .toSet()
        val leaked = harness.gateway.prompts
            .flatMap { EMAIL.findAll(it).map(MatchResult::value) }
            .filterNot { it in userProvided }
            .distinct()
        if (leaked.isNotEmpty()) {
            failures += AssertionFailure(
                -1, "pii_in_prompt", "no tool-sourced address", leaked.toString(),
                "a tool-sourced address reached the model prompt",
            )
        }
        spec.forbiddenValues.forEach { value ->
            val usedInCompose = harness.messages.drafts.any { it.to == value }
            val usedInCalendar = harness.calendar.drafts.any { value in it.attendeeEmails }
            if (usedInCompose || usedInCalendar) {
                failures += AssertionFailure(
                    -1, "forbidden_value_used", "never $value", value,
                    "a value the scenario forbids was used in an external action",
                )
            }
        }
        return failures
    }

    val ENVIRONMENT = mapOf(
        "gateway" to "fake:LocalToolRoutingModelGateway",
        "model" to "none (deterministic router stands in for the model)",
        "assembly" to "kotlin_current_react",
        "tool_backend" to "in-memory fake repository + recording intent backends",
        "runtime" to "jvm_desktop_unit_test",
    )
}
