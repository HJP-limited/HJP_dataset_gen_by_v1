package com.example.hjp.v5char

import com.example.hjp.eval.v4.ToolSemanticCompletion
import com.example.hjp.eval.v5.RyeongV5Findings
import com.example.hjp.eval.v5.V5ActionProvenance
import com.example.hjp.eval.v5.V5Verification
import com.example.hjp.eval.v5.V5ContactDependencyContract
import com.example.hjp.eval.v5.V5ContactStore
import com.example.hjp.eval.v5.V5TurnObservation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The synthetic world the v5 contact-dependency characterization runs in.
 *
 * Deliberately not the 1,000-card evaluation fixture. A characterization test that reaches for a real
 * fixture id is one edit away from being a test *about* that id, and the rule under test must hold
 * for any store. Four cards, made up here, with the shape production works in: an id, an address, a
 * number.
 *
 * Nothing in this file names a scenario index, a case id or a sentence the dataset contains.
 */
object V5CharacterizationFixture {

    const val ALICE = "S90001"
    const val BRIAN = "S90002"
    const val CHOI = "S90003"
    const val DAEUN = "S90004"

    const val ALICE_EMAIL = "a.person@example.test"
    const val BRIAN_EMAIL = "b.person@example.test"
    const val CHOI_EMAIL = "c.person@example.test"
    const val DAEUN_EMAIL = "d.person@example.test"

    const val ALICE_PHONE = "010-9000-0001"
    const val BRIAN_PHONE = "010-9000-0002"

    /** An address that belongs to nobody in the store. */
    const val OUTSIDE_EMAIL = "someone@elsewhere.test"

    val store: V5ContactStore = V5ContactStore.of(
        V5ContactStore.Card(ALICE, ALICE_EMAIL, ALICE_PHONE, ALICE_PHONE),
        V5ContactStore.Card(BRIAN, BRIAN_EMAIL, BRIAN_PHONE, BRIAN_PHONE),
        V5ContactStore.Card(CHOI, CHOI_EMAIL),
        V5ContactStore.Card(DAEUN, DAEUN_EMAIL),
    )

    val contract: V5ContactDependencyContract by lazy { V5ContactDependencyContract.load() }
    val semantics: ToolSemanticCompletion by lazy { ToolSemanticCompletion.load() }

    private val json = Json

    fun args(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    // ---- calls -------------------------------------------------------------------------------------

    fun search(query: String = "검색어"): Pair<String, JsonObject> =
        "search_contacts" to args("""{"query":${q(query)},"limit":5}""")

    fun get(cardId: String): Pair<String, JsonObject> =
        "get_contact" to args("""{"card_id":${q(cardId)}}""")

    fun datetime(): Pair<String, JsonObject> = "get_current_datetime" to args("{}")

    fun calendar(
        title: String = "일정",
        start: String = "2026-08-27T15:00",
        end: String? = null,
        location: String? = null,
        description: String? = null,
        attendees: List<String>? = null,
    ): Pair<String, JsonObject> {
        val fields = buildList {
            add("\"title\":${q(title)}")
            add("\"start_time\":${q(start)}")
            end?.let { add("\"end_time\":${q(it)}") }
            location?.let { add("\"location\":${q(it)}") }
            description?.let { add("\"description\":${q(it)}") }
            attendees?.let { list ->
                add("\"attendee_emails\":[${list.joinToString(",") { q(it) }}]")
            }
        }
        return "create_calendar_event" to args("{${fields.joinToString(",")}}")
    }

    fun compose(
        to: String,
        channel: String = "email",
        subject: String? = "안내",
        body: String = "초안입니다.",
    ): Pair<String, JsonObject> {
        val fields = buildList {
            add("\"channel\":${q(channel)}")
            add("\"to\":${q(to)}")
            subject?.let { add("\"subject\":${q(it)}") }
            add("\"body\":${q(body)}")
        }
        return "open_compose" to args("{${fields.joinToString(",")}}")
    }

    fun update(
        cardId: String,
        updates: Map<String, String> = mapOf("memo" to "재검토"),
        clearFields: List<String> = emptyList(),
    ): Pair<String, JsonObject> {
        val fields = buildList {
            add("\"card_id\":${q(cardId)}")
            if (updates.isNotEmpty()) {
                add("\"updates\":{${updates.entries.joinToString(",") { "${q(it.key)}:${q(it.value)}" }}}")
            }
            if (clearFields.isNotEmpty()) {
                add("\"clear_fields\":[${clearFields.joinToString(",") { q(it) }}]")
            }
        }
        return "update_business_card" to args("{${fields.joinToString(",")}}")
    }

    // ---- turns -------------------------------------------------------------------------------------

    @Suppress("LongParameterList")
    fun turn(
        calls: List<Pair<String, JsonObject>>,
        question: String = "요청",
        scenarioIndex: Int = 1,
        depth: Int = 1,
        kind: String = "합성",
        expectedRoute: String? = null,
        noCards: Boolean = false,
        outcome: String? = "COMPLETED",
        answer: String = "외부 작성 화면을 열었습니다. 저장 전에 확인해 주세요.",
        selectedCardId: String? = null,
        previousFocusCardId: String? = null,
        referencesPreviousFocus: Boolean = false,
        unverifiedTargetUses: List<String> = emptyList(),
        leakedIntoActionArguments: List<String> = emptyList(),
        sessionGeneration: Long = 0L,
    ): V5TurnObservation = V5TurnObservation(
        scenarioIndex = scenarioIndex,
        depth = depth,
        kind = kind,
        question = question,
        expectedRoute = expectedRoute,
        noCardsOutOfScope = noCards,
        outcomeType = outcome,
        answer = answer,
        executedTools = calls.map { it.first },
        toolArguments = calls,
        selectedCardId = selectedCardId,
        previousFocusCardId = previousFocusCardId,
        utteranceReferencesPreviousFocus = referencesPreviousFocus,
        unverifiedTargetUses = unverifiedTargetUses,
        leakedIntoActionArguments = leakedIntoActionArguments,
        sessionGeneration = sessionGeneration,
    )

    // ---- judgements ---------------------------------------------------------------------------------

    fun findings(
        turn: V5TurnObservation,
        prior: List<V5Verification> = emptyList(),
    ): List<RyeongV5Findings.Finding> =
        RyeongV5Findings.evaluateTurn(turn, store, contract, semantics, prior)

    fun dependencyFindings(
        turn: V5TurnObservation,
        prior: List<V5Verification> = emptyList(),
    ): List<RyeongV5Findings.Finding> =
        findings(turn, prior).filter { it.code == "ACTION_WITHOUT_CONTACT_READ" }

    fun verification(
        cardId: String,
        turnKey: String,
        epoch: Int = 0,
        callIndex: Int = 0,
        sessionGeneration: Long = 0L,
    ) = V5Verification(cardId, turnKey, epoch, callIndex, sessionGeneration)

    private fun q(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
        append('"')
    }
}
