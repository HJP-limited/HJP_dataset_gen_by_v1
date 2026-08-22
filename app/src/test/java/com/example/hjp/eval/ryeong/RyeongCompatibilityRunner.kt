package com.example.hjp.eval.ryeong

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.DialogueAct
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.ContactSearchBackend
import kotlinx.coroutines.runBlocking

/** Action tools that a search-only Ryeong turn must never reach. */
private val ACTION_TOOLS = setOf("open_compose", "create_calendar_event", "update_business_card")

data class TurnObservation(
    val scenarioIndex: Int,
    val depth: Int,
    val question: String,
    val expectedRoute: String?,
    val observedAct: DialogueAct,
    val routeScorable: Boolean,
    val routeExclusionReason: String,
    val routePassed: Boolean?,
    val goldCardIds: List<String>,
    /** Ranked IDs the production backend returned, flattened in call order, unmodified. */
    val observedRanking: List<String>,
    val r5Scorable: Boolean,
    val r5Passed: Boolean?,
    val selectedCardId: String?,
    val executedTools: List<String>,
    val unexpectedActionTools: List<String>,
    val retrievalModes: List<String>,
    val outcomeType: String?,
)

data class ScenarioObservation(
    val index: Int,
    val kind: String,
    val depthBucket: String,
    val turnCount: Int,
    val generateOnly: Boolean,
    val turns: List<TurnObservation>,
    /** Focus/candidate state the scenario ended with, used to prove the next one starts clean. */
    val endingSelectedCardId: String?,
    val endingHistoryTurns: Int,
) {
    val routeScorableTurns: Int get() = turns.count { it.routeScorable }
    val routeAllPassed: Boolean?
        get() = if (routeScorableTurns == 0) null else turns.filter { it.routeScorable }.all { it.routePassed == true }
}

data class LeakageCheck(
    val scenarioIndex: Int,
    val startedWithSelectedContact: Boolean,
    val startedWithHistory: Boolean,
) {
    val leaked: Boolean get() = startedWithSelectedContact || startedWithHistory
}

data class RyeongCompatibilityResult(
    val scenarios: List<ScenarioObservation>,
    val leakage: List<LeakageCheck>,
    val cardCount: Int,
    val semanticAvailable: Boolean,
    val retrievalModes: Set<String>,
) {
    val leakageCount: Int get() = leakage.count { it.leaked }
    val unexpectedActionToolCount: Int
        get() = scenarios.sumOf { s -> s.turns.sumOf { it.unexpectedActionTools.size } }
}

/**
 * Runs frozen Ryeong scenarios through the real agent and records what it did.
 *
 * The runner never tells the agent what the answer is. Expected routes, gold card IDs and
 * expected slots are read *after* a turn to score it; the only thing handed to the kernel is the
 * user's sentence. Session state is created fresh per scenario and closed at the end of it, so
 * one scenario cannot see the previous one's focus, history or memory.
 */
class RyeongCompatibilityRunner(
    private val cards: List<BusinessCardRecord>,
    private val backendFactory: (MultiturnScenarioHarness.RecordingRepository) -> ContactSearchBackend,
) {

    fun run(scenarios: List<RyeongScenario>): RyeongCompatibilityResult {
        val observations = mutableListOf<ScenarioObservation>()
        val leakage = mutableListOf<LeakageCheck>()
        val modes = mutableSetOf<String>()

        scenarios.forEach { scenario ->
            // A brand-new harness per scenario: new session store, new memory, new history.
            val harness = MultiturnScenarioHarness(
                cards = cards,
                searchBackendFactory = backendFactory,
            )
            try {
                runBlocking {
                    val opening = harness.session()
                    leakage += LeakageCheck(
                        scenarioIndex = scenario.index,
                        startedWithSelectedContact = opening.conversationMemory.selectedContact != null,
                        startedWithHistory = opening.transcript.isNotEmpty(),
                    )

                    val turns = scenario.turns.map { turn -> observe(harness, scenario, turn, modes) }
                    val closing = harness.session()
                    observations += ScenarioObservation(
                        index = scenario.index,
                        kind = scenario.kind,
                        depthBucket = scenario.depthBucket,
                        turnCount = scenario.turnCount,
                        generateOnly = scenario.generateOnly,
                        turns = turns,
                        endingSelectedCardId = closing.conversationMemory.selectedContact?.cardId,
                        endingHistoryTurns = closing.transcript.size,
                    )
                }
            } finally {
                harness.close()
            }
        }
        return RyeongCompatibilityResult(
            scenarios = observations,
            leakage = leakage,
            cardCount = cards.size,
            semanticAvailable = modes.any { it.contains("HYBRID", ignoreCase = true) },
            retrievalModes = modes,
        )
    }

    private suspend fun observe(
        harness: MultiturnScenarioHarness,
        scenario: RyeongScenario,
        turn: RyeongTurn,
        modes: MutableSet<String>,
    ): TurnObservation {
        // The kernel receives the question and nothing else.
        val record = harness.turn(turn.question)
        modes += record.retrievalModes

        val kind = RyeongTranslation.classify(turn.expectedRoute)
        val scorable = kind == TranslationKind.DERIVED_FROM_PRODUCTION_TRACE
        val routePassed = if (scorable) {
            RyeongTranslation.routeSatisfied(turn.expectedRoute!!, record.act)
        } else {
            null
        }

        // Flattened in call order; no de-duplication, no padding, no injected IDs.
        val ranking = record.searchRankings.flatten()
        val r5Scorable = turn.goldCardIds.isNotEmpty() && record.searchRankings.isNotEmpty()
        val r5Passed = if (r5Scorable) {
            ranking.take(5).any { it in turn.goldCardIds }
        } else {
            null
        }

        val session = harness.session()
        return TurnObservation(
            scenarioIndex = scenario.index,
            depth = turn.depth,
            question = turn.question,
            expectedRoute = turn.expectedRoute,
            observedAct = record.act,
            routeScorable = scorable,
            routeExclusionReason = if (scorable) "" else RyeongTranslation.exclusionReason(turn.expectedRoute),
            routePassed = routePassed,
            goldCardIds = turn.goldCardIds,
            observedRanking = ranking,
            r5Scorable = r5Scorable,
            r5Passed = r5Passed,
            selectedCardId = session.conversationMemory.selectedContact?.cardId,
            executedTools = record.executedTools,
            unexpectedActionTools = record.executedTools.filter { it in ACTION_TOOLS },
            retrievalModes = record.retrievalModes,
            outcomeType = record.outcomeType?.name,
        )
    }
}
