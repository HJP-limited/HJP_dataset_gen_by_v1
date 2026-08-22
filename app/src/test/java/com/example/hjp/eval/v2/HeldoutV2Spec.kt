package com.example.hjp.eval.v2

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord

/**
 * One turn of a held-out v2 scenario.
 *
 * The difference from the v1 spec is that a turn now has to say *which kind of claim* each of its
 * assertions is, because v2 reports formal, behavioural and response success as separate numbers.
 * A turn that only declares a route can no longer be counted as evidence that the agent answered
 * correctly — it never was evidence of that, but v1 had no field with which to say so.
 */
data class V2Turn(
    val user: String,

    // ---- formal contract: what kind of turn this is -------------------------------------------
    val act: DialogueAct,
    val outcome: TurnOutcomeType,

    // ---- behaviour: what the turn is allowed to do ---------------------------------------------
    /** The exact tool trace, in order. Empty means the turn must run no tool at all. */
    val tools: List<String> = emptyList(),
    /** Tools that must not run, asserted even where [tools] already implies it. */
    val forbidden: Set<String> = emptySet(),
    /** tool -> argument key -> required value. */
    val args: Map<String, Map<String, String>> = emptyMap(),
    /**
     * tool -> argument key -> pattern the value must match.
     *
     * For arguments whose exact value is a function of the day the suite runs. A relative-date case
     * still pins its reference date (see `HeldoutV2Cases.REFERENCE_DATE`); this is for the parts an
     * exact string cannot express, such as "the time of day must be 15:00 whatever the date is".
     */
    val argPatterns: Map<String, Map<String, String>> = emptyMap(),
    /** Compose screens + calendar screens + card writes this turn may perform. */
    val sideEffects: Int = 0,
    val composeTo: String? = null,
    val calendarAttendees: List<String>? = null,
    val selectedCardId: String? = null,
    val expectNoSelected: Boolean = false,
    val candidateIds: List<String>? = null,

    // ---- response: what the user must be told ---------------------------------------------------
    val answerContains: List<String> = emptyList(),
    val answerExcludes: List<String> = emptyList(),
    /** At least one member of each group must appear. Lets a claim be worded more than one way. */
    val answerContainsAnyOf: List<List<String>> = emptyList(),

    // ---- typed obligations, so a denominator can be built from real cases only -------------------
    /**
     * This turn genuinely lacks a slot the tool requires, so it must stop and ask. Only turns
     * carrying this flag enter the required-slot denominator — v1 counted every no-tool turn there,
     * which quietly inflated the metric with turns that had nothing missing.
     */
    val missingRequiredSlot: Boolean = false,
    /** The request names something this agent cannot do; the answer must say so. */
    val unsupportedRequest: Boolean = false,
    /** A general-knowledge question; the answer must engage with [semanticTopic]. */
    val generalInformation: Boolean = false,
    /** Topic the answer has to name for a [generalInformation] turn. */
    val semanticTopic: String? = null,
    /** The user is recalling a past request; the answer must come from the transcript. */
    val quotedRecall: Boolean = false,
    /** `새 대화` is issued immediately before this turn. */
    val resetBefore: Boolean = false,
) {
    val hasAnswerAssertion: Boolean
        get() = answerContains.isNotEmpty() || answerExcludes.isNotEmpty() ||
            answerContainsAnyOf.isNotEmpty()

    /** A turn whose safety matters: it forbids something, or its category is a refusal. */
    val isSafetyRelevant: Boolean
        get() = forbidden.isNotEmpty() || unsupportedRequest || quotedRecall ||
            generalInformation || missingRequiredSlot
}

/**
 * One held-out v2 scenario. Every scenario is multi-turn by construction; the validation refuses to
 * build a suite containing a single-turn case at all.
 */
data class V2Scenario(
    val id: String,
    val category: String,
    val tags: List<String> = emptyList(),
    val turns: List<V2Turn>,
    val cards: List<BusinessCardRecord> = MultiturnScenarioHarness.DEFAULT_CARDS,
    val searchFailure: Boolean = false,
    val confirmUpdates: Boolean = true,
    /** Values that must never reach an external action in this scenario. */
    val forbiddenValues: List<String> = emptyList(),
    /** Why this scenario exists — checked by validation so no case is shape without purpose. */
    val intent: String,
) {
    val userTurnCount: Int get() = turns.size

    /** Shape only: acts, outcomes and tool traces, ignoring who and what. */
    fun signature(): String = turns.joinToString("|") { t ->
        "${t.act}>${t.outcome}>${t.tools.joinToString("+")}" + if (t.resetBefore) "|RESET" else ""
    }
}

/**
 * Held-out v2 categories.
 *
 * [MINIMUMS] is enforced by counting the scenarios that actually carry each category, not by
 * checking that the map has a key — a floor nobody counts against is not a floor.
 */
object V2Categories {
    const val ANAPHORA = "contact_anaphora_and_focus"
    const val FOCUS_TARGET = "focus_vs_turn_target"
    const val TARGET_SWITCH = "target_switch"
    const val CHAIN_COMPOSE = "search_detail_compose"
    const val CHAIN_CALENDAR = "search_detail_calendar"
    const val CHAIN_UPDATE = "search_then_update"
    const val AFTER_ACTION = "unrelated_action_after_action"
    const val SLOT = "required_slot_and_clarification"
    const val DATETIME = "datetime_expressions"
    const val DATES = "absolute_and_relative_dates"
    const val INFORMATION = "general_information"
    const val UNSUPPORTED = "unsupported_action"
    const val RECALL = "quoted_recall"
    const val FIELD = "selected_contact_field"
    const val COLLISION = "name_action_word_collision"
    const val NO_TOOL = "no_tool_conversation"
    const val RESET = "cancel_reset_new_session"
    const val MULTI_TOOL = "multi_tool_chain"
    const val STALE = "stale_target_prevention"
    const val IDEMPOTENCY = "duplicate_side_effect_prevention"
    const val TRUTHFUL = "no_false_completion"
    const val PROVENANCE = "contact_value_provenance"
    const val SAFE_FAILURE = "safe_failure"

    /** Floors, in scenarios. Every key is counted for real in [HeldoutV2ValidationTest]. */
    val MINIMUMS: Map<String, Int> = linkedMapOf(
        ANAPHORA to 5,
        FOCUS_TARGET to 6,
        TARGET_SWITCH to 3,
        CHAIN_COMPOSE to 4,
        CHAIN_CALENDAR to 3,
        CHAIN_UPDATE to 3,
        AFTER_ACTION to 4,
        SLOT to 5,
        DATETIME to 4,
        DATES to 3,
        INFORMATION to 4,
        UNSUPPORTED to 4,
        RECALL to 3,
        FIELD to 3,
        COLLISION to 2,
        NO_TOOL to 2,
        RESET to 2,
        MULTI_TOOL to 4,
        STALE to 2,
        IDEMPOTENCY to 2,
        TRUTHFUL to 2,
        PROVENANCE to 2,
        SAFE_FAILURE to 2,
    )

    val ALL: Set<String> = MINIMUMS.keys
}

/** Tool names the v2 suite is allowed to mention at all. */
object V2Tools {
    const val SEARCH = "search_contacts"
    const val GET = "get_contact"
    const val COMPOSE = "open_compose"
    const val CALENDAR = "create_calendar_event"
    const val UPDATE = "update_business_card"
    const val NOW = "get_current_datetime"

    val ALLOWLIST: Set<String> = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE, NOW)

    val SIDE_EFFECTING: Set<String> = setOf(COMPOSE, CALENDAR, UPDATE)

    /** Arguments each tool must carry when a case asserts on it. */
    val REQUIRED_ARGS: Map<String, Set<String>> = mapOf(
        SEARCH to setOf("query"),
        GET to setOf("card_id"),
        COMPOSE to setOf("channel", "to", "body"),
        CALENDAR to setOf("title", "start_time"),
        UPDATE to setOf("card_id"),
        NOW to emptySet(),
    )

    /** Argument key -> the shape its asserted value must have. */
    val ARG_SHAPES: Map<String, Regex> = mapOf(
        "start_time" to Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}"""),
        "channel" to Regex("email|sms"),
        "card_id" to Regex("""[A-Za-z0-9_-]{2,32}"""),
    )
}
