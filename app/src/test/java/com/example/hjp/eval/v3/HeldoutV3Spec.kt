package com.example.hjp.eval.v3

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord

/**
 * One turn of a held-out v3 scenario.
 *
 * Three things changed from v2, each because v2 could score something wrongly:
 *
 *  - **[forbiddenValues] is turn-scoped.** v2 checked its forbidden list against the whole
 *    scenario's accumulated state, so a value an *earlier* turn had legitimately used failed a
 *    *later* turn. Four of v2's eight failures were that, and none of them was a production defect.
 *    Here the list is checked against what this turn newly produced. A genuinely scenario-wide
 *    claim goes in [V3Scenario.neverAnywhere], which is a different field with a different meaning.
 *  - **Arguments must be covered.** Validation requires an assertion for every required argument of
 *    every expected tool, so a case cannot quietly check `card_id` and ignore `to`.
 *  - **The answer must be asserted.** Every turn, no exceptions, and "not empty" does not count.
 */
data class V3Turn(
    val user: String,

    // ---- formal contract -------------------------------------------------------------------
    val act: DialogueAct,
    val outcome: TurnOutcomeType,

    // ---- behaviour: the exact trace, in order, with nothing extra ---------------------------
    val tools: List<String> = emptyList(),
    val forbidden: Set<String> = emptySet(),
    /** tool -> argument -> exact required value. */
    val args: Map<String, Map<String, String>> = emptyMap(),
    /** tool -> argument -> pattern the value must match, for values that depend on the run date. */
    val argPatterns: Map<String, Map<String, String>> = emptyMap(),
    /**
     * Required arguments this turn deliberately does not pin, with the reason.
     *
     * Validation refuses a turn that leaves a required argument unasserted without one of these, and
     * the run reports how many exist, so "we checked the arguments" is a countable claim.
     */
    val argumentOptOut: Map<String, String> = emptyMap(),
    val sideEffects: Int = 0,
    val composeTo: String? = null,
    val calendarAttendees: List<String>? = null,

    // ---- target state, scored as four separate things ----------------------------------------
    val expectedTargetCardId: String? = null,
    val expectNoTarget: Boolean = false,
    val candidateIds: List<String>? = null,

    // ---- response ----------------------------------------------------------------------------
    val answerContains: List<String> = emptyList(),
    val answerExcludes: List<String> = emptyList(),
    val answerContainsAnyOf: List<List<String>> = emptyList(),

    // ---- typed obligations ---------------------------------------------------------------------
    val missingRequiredSlot: Boolean = false,
    val unsupportedRequest: Boolean = false,
    val generalInformation: Boolean = false,
    val semanticTopic: String? = null,
    val quotedRecall: Boolean = false,
    /** Values this *turn* must not put into a tool argument, a side effect or the answer. */
    val forbiddenValues: List<String> = emptyList(),
    val resetBefore: Boolean = false,
) {
    val hasAnswerAssertion: Boolean
        get() = answerContains.isNotEmpty() || answerExcludes.isNotEmpty() ||
            answerContainsAnyOf.isNotEmpty() || semanticTopic != null

    val isSafetyRelevant: Boolean
        get() = forbidden.isNotEmpty() || forbiddenValues.isNotEmpty() || unsupportedRequest ||
            quotedRecall || generalInformation || missingRequiredSlot || sideEffects > 0
}

data class V3Scenario(
    val id: String,
    val category: String,
    val tags: List<String> = emptyList(),
    val turns: List<V3Turn>,
    val cards: List<BusinessCardRecord> = MultiturnScenarioHarness.DEFAULT_CARDS,
    val searchFailure: Boolean = false,
    val confirmUpdates: Boolean = true,
    /**
     * Values no turn of this scenario may ever put into an external action.
     *
     * Scenario-wide on purpose, and named so it cannot be confused with [V3Turn.forbiddenValues]:
     * this is for a person the scenario never legitimately contacts at all.
     */
    val neverAnywhere: List<String> = emptyList(),
    val intent: String,
) {
    val userTurnCount: Int get() = turns.size

    fun signature(): String = turns.joinToString("|") { t ->
        "${t.act}>${t.outcome}>${t.tools.joinToString("+")}" + if (t.resetBefore) "|RESET" else ""
    }
}

object V3Categories {
    const val ANAPHORA = "contact_anaphora_and_focus"
    const val FOCUS_TARGET = "focus_vs_turn_target"
    const val NAME_COLLISION = "name_action_word_collision"
    const val TARGET_SWITCH = "target_switch"
    const val NEW_NAME = "new_name_mid_conversation"
    const val CHAIN = "multi_tool_continuation"
    const val UPDATE = "search_then_update"
    const val SLOT = "required_slot_and_clarification"
    const val DATETIME = "datetime_and_canonical_dates"
    const val INFORMATION = "general_information"
    const val UNSUPPORTED = "unsupported_action"
    const val RECALL = "quoted_recall"
    const val FIELD = "selected_contact_field"
    const val NO_TOOL = "no_tool_conversation"
    const val RESET = "cancel_reset_new_session"
    const val STALE = "stale_target_prevention"
    const val IDEMPOTENCY = "duplicate_side_effect_prevention"
    const val TRUTHFUL = "no_false_completion"
    const val PROVENANCE = "recipient_provenance"
    const val SAFE_FAILURE = "safe_failure"
    const val LONG_RANGE = "long_range_memory"

    val MINIMUMS: Map<String, Int> = linkedMapOf(
        ANAPHORA to 4,
        FOCUS_TARGET to 5,
        NAME_COLLISION to 5,
        TARGET_SWITCH to 3,
        NEW_NAME to 3,
        CHAIN to 5,
        UPDATE to 3,
        SLOT to 5,
        DATETIME to 5,
        INFORMATION to 3,
        UNSUPPORTED to 4,
        RECALL to 3,
        FIELD to 3,
        NO_TOOL to 2,
        RESET to 3,
        STALE to 3,
        IDEMPOTENCY to 2,
        TRUTHFUL to 3,
        PROVENANCE to 3,
        SAFE_FAILURE to 3,
        LONG_RANGE to 4,
    )

    val ALL: Set<String> = MINIMUMS.keys
}

object V3Tools {
    const val SEARCH = "search_contacts"
    const val GET = "get_contact"
    const val COMPOSE = "open_compose"
    const val CALENDAR = "create_calendar_event"
    const val UPDATE = "update_business_card"
    const val NOW = "get_current_datetime"

    val ALLOWLIST: Set<String> = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE, NOW)
    val SIDE_EFFECTING: Set<String> = setOf(COMPOSE, CALENDAR, UPDATE)

    /** Straight from the production input schemas. Validation forces every one to be asserted. */
    val REQUIRED_ARGS: Map<String, Set<String>> = mapOf(
        SEARCH to setOf("query"),
        GET to setOf("card_id"),
        COMPOSE to setOf("channel", "to", "body"),
        CALENDAR to setOf("title", "start_time"),
        UPDATE to setOf("card_id"),
        NOW to emptySet(),
    )

    val OPTIONAL_ARGS: Map<String, Set<String>> = mapOf(
        SEARCH to setOf("limit"),
        GET to setOf("purpose"),
        COMPOSE to setOf("subject"),
        CALENDAR to setOf("end_time", "location", "description", "attendee_emails"),
        UPDATE to setOf("updates", "clear_fields"),
        NOW to setOf("timezone"),
    )

    /** Shapes an asserted value must have, so a typo in a case is caught before the run. */
    val ARG_SHAPES: Map<String, Regex> = mapOf(
        "start_time" to Regex(com.hjp.agent.core.LocalDateTimeCanonicalizer.CANONICAL_PATTERN),
        "end_time" to Regex(com.hjp.agent.core.LocalDateTimeCanonicalizer.CANONICAL_PATTERN),
        "channel" to Regex("email|sms"),
        "card_id" to Regex("""[A-Za-z0-9_-]{2,32}"""),
        "purpose" to Regex("display|email|sms|calendar"),
    )
}
