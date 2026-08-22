package com.example.hjp.eval.ryeong

import com.hjp.agent.contract.DialogueAct

/** How a Ryeong concept relates to something this agent actually produces. */
enum class TranslationKind {
    /** The production trace carries the same thing under a different name. */
    DIRECT,

    /** Computable from the production trace without inventing anything. */
    DERIVED_FROM_PRODUCTION_TRACE,

    /** Mappable, but the mapping throws information away in at least one direction. */
    LOSSY,

    /** Production has no observable equivalent today. Scored as neither pass nor fail. */
    NOT_SCORABLE,

    /** The concept does not exist on one side, and inventing it would be a fabrication. */
    NOT_APPLICABLE,
}

data class TranslationEntry(
    val concept: String,
    val kind: TranslationKind,
    val rationale: String,
)

/**
 * The Ryeong-to-production translation, decided once and applied everywhere.
 *
 * Two rules govern everything here. A route is only mapped when the two sides mean the same
 * thing — never because the names look similar. And when production cannot be observed, the
 * answer is [TranslationKind.NOT_SCORABLE], never a guess that happens to match.
 */
object RyeongTranslation {

    /**
     * Upstream route -> the acts that legitimately realise it.
     *
     * A route maps to a *set* because production classifies more finely than upstream does.
     * Routes absent from this map are deliberately unmapped, not forgotten.
     */
    private val ROUTE_TO_ACTS: Map<String, Set<DialogueAct>> = mapOf(
        // A fresh lookup on both sides.
        "search" to setOf(DialogueAct.CONTACT_SEARCH),
        // Upstream's followup is a field question about the person already in focus, which is
        // exactly what CONTACT_DETAIL is. CONTACT_SELECTION is included because upstream has no
        // separate notion of picking among candidates; both are "stay on this conversation's
        // person and answer about them".
        "followup" to setOf(DialogueAct.CONTACT_DETAIL, DialogueAct.CONTACT_SELECTION),
    )

    /**
     * Routes with no honest production counterpart.
     *
     * Each of these is unmapped for a stated reason, not for lack of a similar-sounding name.
     */
    private val UNMAPPED: Map<String, String> = mapOf(
        "empty_result" to
            "upstream encodes 'searched and found nothing' as a route; production keeps the act " +
            "(CONTACT_SEARCH) and expresses emptiness in the outcome, so the two axes do not line up",
        "abstain" to
            "one upstream route covers what production splits across CLARIFICATION_REQUIRED and " +
            "UNSUPPORTED; picking either would decide a policy question the mapping has no standing to decide",
        "context_answer" to
            "spans ANSWER_FROM_HISTORY and QUOTED_RECALL in production; same one-to-many problem",
        "filtered_count" to "production has no aggregate-count route at all",
        "total_count" to "production has no aggregate-count route at all",
        "self_reference" to
            "upstream answers questions about the user themselves from server-side state; " +
            "production has no equivalent route and would treat these as general information",
    )

    /** Whether this expected route can be scored at all, and why. */
    fun classify(expectedRoute: String?): TranslationKind = when {
        expectedRoute == null -> TranslationKind.NOT_APPLICABLE
        ROUTE_TO_ACTS.containsKey(expectedRoute) -> TranslationKind.DERIVED_FROM_PRODUCTION_TRACE
        UNMAPPED.containsKey(expectedRoute) -> TranslationKind.NOT_SCORABLE
        else -> TranslationKind.NOT_SCORABLE
    }

    fun exclusionReason(expectedRoute: String?): String = when {
        expectedRoute == null -> "the scenario asserts no route for this turn"
        ROUTE_TO_ACTS.containsKey(expectedRoute) -> ""
        else -> UNMAPPED[expectedRoute] ?: "route '$expectedRoute' has no declared mapping"
    }

    /**
     * Does the observed act satisfy the expected route?
     *
     * Only ever called for routes [classify] admitted, so an unmapped route can never be counted
     * as either a pass or a failure.
     */
    fun routeSatisfied(expectedRoute: String, observed: DialogueAct): Boolean =
        ROUTE_TO_ACTS[expectedRoute]?.contains(observed) == true

    /** Every acceptable act for a route, for the confusion matrix's benefit. */
    fun acceptedActs(expectedRoute: String): Set<DialogueAct> =
        ROUTE_TO_ACTS[expectedRoute].orEmpty()

    /** The decided contract, in the order the report presents it. */
    val CONTRACT: List<TranslationEntry> = listOf(
        TranslationEntry("route: search", TranslationKind.DERIVED_FROM_PRODUCTION_TRACE,
            "DialogueAct.CONTACT_SEARCH observed from the production router"),
        TranslationEntry("route: followup", TranslationKind.DERIVED_FROM_PRODUCTION_TRACE,
            "CONTACT_DETAIL or CONTACT_SELECTION; upstream does not distinguish them"),
        TranslationEntry("route: empty_result", TranslationKind.NOT_SCORABLE, UNMAPPED.getValue("empty_result")),
        TranslationEntry("route: abstain", TranslationKind.NOT_SCORABLE, UNMAPPED.getValue("abstain")),
        TranslationEntry("route: context_answer", TranslationKind.NOT_SCORABLE, UNMAPPED.getValue("context_answer")),
        TranslationEntry("route: filtered_count", TranslationKind.NOT_SCORABLE, UNMAPPED.getValue("filtered_count")),
        TranslationEntry("route: total_count", TranslationKind.NOT_SCORABLE, UNMAPPED.getValue("total_count")),
        TranslationEntry("route: self_reference", TranslationKind.NOT_SCORABLE, UNMAPPED.getValue("self_reference")),
        TranslationEntry("typed outcome", TranslationKind.NOT_APPLICABLE,
            "upstream has no outcome axis; production's TurnOutcomeType is recorded but never scored here"),
        TranslationEntry("focus / selected card id", TranslationKind.DIRECT,
            "ConversationMemory.selectedContact.cardId, observed after the turn"),
        TranslationEntry("previous card ids", TranslationKind.DERIVED_FROM_PRODUCTION_TRACE,
            "the ranked IDs the production backend returned on the previous turn"),
        TranslationEntry("field filter: names", TranslationKind.NOT_SCORABLE,
            "production's search_contacts takes one free-text query; the structured constraint is " +
                "computed inside search-core in a package-private plan with no observation seam"),
        TranslationEntry("field filter: titles", TranslationKind.NOT_SCORABLE, "same as names"),
        TranslationEntry("field filter: locations", TranslationKind.NOT_SCORABLE, "same as names"),
        TranslationEntry("top-5 candidates", TranslationKind.DIRECT,
            "TracingContactBackend records the ranking the production backend returned, unmodified"),
        TranslationEntry("no-card suppression", TranslationKind.NOT_SCORABLE,
            "upstream only judges this after generation; no model was executed"),
        TranslationEntry("generated answer", TranslationKind.NOT_SCORABLE,
            "requires actual model generation, which this run did not perform"),
        TranslationEntry("conversation depth", TranslationKind.DIRECT,
            "turn count per scenario, preserved by the export"),
    )
}
