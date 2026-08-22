package com.example.hjp.integration

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.tool.contact.BusinessCardRecord
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answering from memory, and going back to the card — and telling the two apart.
 *
 * Two different questions live very close together here. "아까 회사가 어디라고 했지?" asks what was
 * already established; "지금 회사 정보를 다시 검색해줘" asks for the current truth. Getting the first
 * one wrong wastes a lookup and can silently change which person is in focus; getting the second one
 * wrong answers a question about *now* with a value from *then*.
 *
 * ## Every count here is one turn's
 *
 * The setup turns in these scenarios really do search and read cards — that is what puts a contact
 * in focus. So a session-wide tool count proves nothing. Every assertion below reads
 * `TurnRecord.executedTools`, which the harness clears at the start of each turn, so it is the
 * delta for the turn under test and nothing else.
 *
 * ## The harness is the production path
 *
 * `MultiturnScenarioHarness` runs the real `AgentKernel`, the real `DeterministicTurnRouter`, the
 * real session and memory, and the real `ModelContextSelector`. The model gateway records what it
 * was asked and answers generically; it never decides whether a tool runs. Tool dispatch is the
 * kernel's, and the recording executor only observes it.
 *
 * ## This is a characterization
 *
 * Written before any fix. A failure is the finding. One of these contracts is already known to
 * conflict with a documented production decision — see `expected_tool_contract.json`, written
 * before this file was run.
 */
class HistoricalRecallFreshLookupCharacterizationTest {

    // Two different people, so nothing here can be satisfied by one hardcoded name or id.
    private val hana = BusinessCardRecord(
        id = "HR001", name = "표하윤", company = "너울건설", title = "안전관리",
        industry = "건설", email = "hayun@example.invalid", mobile = "010-0000-0001",
    )
    private val duri = BusinessCardRecord(
        id = "HR002", name = "정한별", company = "새길테크", title = "책임연구원",
        industry = "it", email = "hanbyeol@example.invalid", mobile = "010-0000-0002",
    )

    /** Two people who share a name, so a past reference cannot be resolved by guessing. */
    private val namesakeA = BusinessCardRecord(
        id = "HR010", name = "김서준", company = "비전글로벌", title = "대표이사",
        industry = "finance", email = "seojun1@example.invalid", mobile = "010-0000-0010",
    )
    private val namesakeB = BusinessCardRecord(
        id = "HR011", name = "김서준", company = "한들미디어", title = "선임연구원",
        industry = "media", email = "seojun2@example.invalid", mobile = "010-0000-0011",
    )

    private val userCompany = "큐리악스제타"

    private fun harness(cards: List<BusinessCardRecord> = listOf(hana, duri)) =
        MultiturnScenarioHarness(cards = cards)

    // ---- one turn's tool calls ---------------------------------------------------------------

    private val trackedTools = listOf(
        "search_contacts", "get_contact", "update_business_card", "open_compose", "create_calendar_event",
    )

    /** The tools this turn ran, and only this turn: the harness clears the trace per turn. */
    private fun deltas(record: MultiturnScenarioHarness.TurnRecord): Map<String, Int> {
        val counted = record.executedTools.groupingBy { it }.eachCount()
        val out = LinkedHashMap<String, Int>()
        trackedTools.forEach { out[it] = counted[it] ?: 0 }
        counted.keys.filterNot { it in trackedTools }.forEach { out[it] = counted[it] ?: 0 }
        return out
    }

    private fun sideEffects(record: MultiturnScenarioHarness.TurnRecord): Int {
        val d = deltas(record)
        return (d["open_compose"] ?: 0) + (d["create_calendar_event"] ?: 0) +
            (d["update_business_card"] ?: 0)
    }

    /** Records what a turn did, so the evidence quotes observations rather than describing them. */
    private fun observe(
        case: String,
        record: MultiturnScenarioHarness.TurnRecord,
        harness: MultiturnScenarioHarness,
        note: String = "",
    ) {
        val prompt = harness.gateway.prompts.lastOrNull().orEmpty()
        OBSERVATIONS += buildString {
            append("  {\"case\": ").append(quote(case))
            append(", \"query\": ").append(quote(record.text))
            append(", \"act\": ").append(quote(record.act.name))
            append(", \"outcome\": ").append(quote(record.outcomeType?.name ?: ""))
            append(", \"tool_delta\": {")
            append(deltas(record).entries.joinToString(", ") { "${quote(it.key)}: ${it.value}" })
            append("}, \"side_effect_count\": ").append(sideEffects(record))
            append(", \"answer\": ").append(quote(record.answer.take(300)))
            append(", \"model_request_had_a_state_section\": ").append(prompt.contains("[session_state]"))
            append(", \"note\": ").append(quote(note)).append("}")
        }
    }

    // ---- 7.1 the user's own remembered fact ----------------------------------------------------

    /** Three ways of asking the same thing, none of them the sentence that stated the fact. */
    private fun ownFactRecallPhrasings() = listOf(
        "아까 회사가 어디라고 했지?",
        "내가 다닌다고 한 회사가 어디였지?",
        "전에 알려준 소속이 어디였지?",
    )

    @Test
    fun `recalling a fact the user stated runs no tool at all`() = runBlocking {
        val violations = mutableListOf<String>()
        ownFactRecallPhrasings().forEach { question ->
            val harness = harness()
            try {
                harness.turn("내 회사는 ${userCompany}이야.")
                val record = harness.turn(question)
                observe("own_fact_recall", record, harness)

                val d = deltas(record)
                val ran = d.filterValues { it > 0 }
                if (ran.isNotEmpty()) {
                    violations += "\"$question\" ran $ran"
                }
            } finally {
                harness.close()
            }
        }
        assertEquals(
            "a question about what was already said is answered from the session, not by looking " +
                "anything up:\n  " + violations.joinToString("\n  "),
            emptyList<String>(), violations,
        )
    }

    @Test
    fun `the remembered company is in the model request for the recall turn`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("내 회사는 ${userCompany}이야.")
            val before = harness.gateway.prompts.size
            harness.turn(ownFactRecallPhrasings().first())
            val produced = harness.gateway.prompts.drop(before)

            assertTrue(
                "the recall turn must have reached the model exactly once for this to mean anything",
                produced.size <= 1,
            )
            // A turn answered deterministically never reaches the model; then the fact has to be in
            // memory instead. Either way the answer must rest on something recorded, not invented.
            val evidence = if (produced.isEmpty()) {
                harness.session().conversationMemory.confirmedFacts.joinToString { it.content }
            } else {
                produced.single()
            }
            assertTrue(
                "the answer must rest on the remembered fact: $evidence",
                evidence.contains(userCompany),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `the recall answer names the remembered company`() = runBlocking {
        val violations = mutableListOf<String>()
        ownFactRecallPhrasings().forEach { question ->
            val harness = harness()
            try {
                harness.turn("내 회사는 ${userCompany}이야.")
                val record = harness.turn(question)
                if (!record.answer.contains(userCompany)) {
                    violations += "\"$question\" answered \"${record.answer.take(120)}\""
                }
            } finally {
                harness.close()
            }
        }
        assertEquals(
            "the point of remembering it is being able to say it back:\n  " +
                violations.joinToString("\n  "),
            emptyList<String>(), violations,
        )
    }

    // ---- 7.2 a fact about a contact already established ------------------------------------------

    /** Puts one contact in focus through the ordinary flow, then asks about it. */
    private suspend fun withEstablishedContact(
        person: BusinessCardRecord,
        question: String,
        body: (MultiturnScenarioHarness, MultiturnScenarioHarness.TurnRecord) -> Unit,
    ) {
        val harness = harness()
        try {
            harness.turn("${person.name} 명함 찾아줘.")
            harness.turn("그분 회사가 어디야?")
            val record = harness.turn(question)
            body(harness, record)
        } finally {
            harness.close()
        }
    }

    private fun contactRecallPhrasings() = listOf(
        "아까 그 사람 회사가 어디라고 했지?",
        "아까 본 분의 소속이 어디라고 했지?",
        "전에 찾은 명함의 회사가 어디라고 했지?",
    )

    @Test
    fun `recalling an established contacts company runs no search`() = runBlocking {
        val violations = mutableListOf<String>()
        listOf(hana, duri).forEach { person ->
            contactRecallPhrasings().forEach { question ->
                withEstablishedContact(person, question) { harness, record ->
                    observe("contact_recall_search", record, harness, note = person.id)
                    val searched = deltas(record)["search_contacts"] ?: 0
                    if (searched > 0) violations += "${person.id} \"$question\" searched $searched times"
                }
            }
        }
        assertEquals(
            "the person is already established; asking what was said about them must not go looking " +
                "for them again:\n  " + violations.joinToString("\n  "),
            emptyList<String>(), violations,
        )
    }

    @Test
    fun `recalling an established contacts company reads no card`() = runBlocking {
        val violations = mutableListOf<String>()
        listOf(hana, duri).forEach { person ->
            contactRecallPhrasings().forEach { question ->
                withEstablishedContact(person, question) { harness, record ->
                    observe("contact_recall_get", record, harness, note = person.id)
                    val read = deltas(record)["get_contact"] ?: 0
                    if (read > 0) violations += "${person.id} \"$question\" read the card $read times"
                }
            }
        }
        assertEquals(
            "a question phrased as a recall asks what was already said, so it must be answered from " +
                "what was said:\n  " + violations.joinToString("\n  "),
            emptyList<String>(), violations,
        )
    }

    @Test
    fun `a contact recall never answers with a different person`() = runBlocking {
        val violations = mutableListOf<String>()
        listOf(hana to duri, duri to hana).forEach { (subject, other) ->
            withEstablishedContact(subject, contactRecallPhrasings().first()) { _, record ->
                if (record.answer.contains(other.company) || record.answer.contains(other.name)) {
                    violations += "asked about ${subject.id} but the answer mentioned ${other.id}: " +
                        record.answer.take(160)
                }
            }
        }
        assertEquals(
            "answering about the wrong person is the failure this whole area exists to prevent:\n  " +
                violations.joinToString("\n  "),
            emptyList<String>(), violations,
        )
    }

    @Test
    fun `a contact recall runs no side effect`() = runBlocking {
        val violations = mutableListOf<String>()
        listOf(hana, duri).forEach { person ->
            withEstablishedContact(person, contactRecallPhrasings().first()) { _, record ->
                if (sideEffects(record) > 0) {
                    violations += "${person.id} ran ${sideEffects(record)} side effects on a question"
                }
            }
        }
        assertEquals("a question does nothing to the world:\n  " + violations.joinToString("\n  "),
            emptyList<String>(), violations)
    }

    // ---- 7.3 asking for the current truth, explicitly -------------------------------------------

    @Test
    fun `an explicit search request actually searches`() = runBlocking {
        val phrasings = listOf(
            "${hana.name} 최신 회사 정보를 검색해줘.",
            "${hana.name} 지금 정보를 다시 찾아줘.",
            "${hana.name} 현재 소속을 검색해서 확인해줘.",
        )
        val violations = mutableListOf<String>()
        phrasings.forEach { question ->
            val harness = harness()
            try {
                harness.turn("${hana.name} 명함 찾아줘.")
                harness.turn("그분 회사가 어디야?")
                val record = harness.turn(question)
                observe("explicit_search", record, harness)
                val d = deltas(record)
                val searched = d["search_contacts"] ?: 0
                if (searched < 1) violations += "\"$question\" searched $searched times"
                if (searched > 3) violations += "\"$question\" searched $searched times — unbounded"
                if (sideEffects(record) > 0) violations += "\"$question\" ran a side effect"
            } finally {
                harness.close()
            }
        }
        assertEquals(
            "asking for current information must go and get it, not answer from memory:\n  " +
                violations.joinToString("\n  "),
            emptyList<String>(), violations,
        )
    }

    @Test
    fun `an explicit fresh read request actually reads the card`() = runBlocking {
        val phrasings = listOf(
            "지금 상세 정보를 다시 조회해줘.",
            "현재 명함 정보를 다시 확인해줘.",
        )
        val violations = mutableListOf<String>()
        phrasings.forEach { question ->
            val harness = harness()
            try {
                harness.turn("${hana.name} 명함 찾아줘.")
                val record = harness.turn(question)
                observe("explicit_fresh_read", record, harness)
                val d = deltas(record)
                if ((d["get_contact"] ?: 0) < 1) {
                    violations += "\"$question\" read the card ${d["get_contact"]} times"
                }
                if (sideEffects(record) > 0) violations += "\"$question\" ran a side effect"
            } finally {
                harness.close()
            }
        }
        assertEquals(
            "asking for the current card must read the card:\n  " + violations.joinToString("\n  "),
            emptyList<String>(), violations,
        )
    }

    // ---- 7.5 nothing to recall ---------------------------------------------------------------------

    @Test
    fun `a recall with nothing remembered does not invent anybody`() = runBlocking {
        val harness = harness()
        try {
            val record = harness.turn("아까 그 사람 회사가 어디라고 했지?")
            observe("no_memory_recall", record, harness)

            val d = deltas(record)
            val violations = mutableListOf<String>()
            if ((d["search_contacts"] ?: 0) > 0) violations += "searched for a person nobody named"
            if ((d["get_contact"] ?: 0) > 0) violations += "read a card nobody chose"
            if (sideEffects(record) > 0) violations += "ran a side effect"
            listOf(hana, duri).forEach { person ->
                if (record.answer.contains(person.name) || record.answer.contains(person.company)) {
                    violations += "named ${person.id} out of nowhere"
                }
            }
            assertEquals(
                "with nothing established, the honest answer is a question — not a guess: " +
                    "answer was \"${record.answer.take(160)}\"\n  " + violations.joinToString("\n  "),
                emptyList<String>(), violations,
            )
            assertTrue(
                "and the turn must end by asking or by saying it does not know, not silently: " +
                    record.answer,
                record.answer.isNotBlank(),
            )
        } finally {
            harness.close()
        }
    }

    // ---- 7.6 an ambiguous past contact ----------------------------------------------------------

    @Test
    fun `an ambiguous past contact is not resolved by guessing`() = runBlocking {
        val harness = harness(listOf(namesakeA, namesakeB))
        try {
            val search = harness.turn("${namesakeA.name} 명함 찾아줘.")
            assertTrue(
                "the fixture only tests something if the search really was ambiguous: " +
                    "${search.memory.candidateContacts.map { it.cardId }} / ${search.answer.take(120)}",
                search.memory.selectedContact?.isActionable != true,
            )
            val record = harness.turn("아까 그 사람 회사가 어디라고 했지?")
            observe("ambiguous_recall", record, harness)

            val violations = mutableListOf<String>()
            if ((deltas(record)["get_contact"] ?: 0) > 0) violations += "read one of the candidates' cards"
            if (sideEffects(record) > 0) violations += "ran a side effect"
            val named = listOf(namesakeA, namesakeB).filter { record.answer.contains(it.company) }
            if (named.isNotEmpty()) {
                violations += "stated ${named.map { it.id }} as the answer while the person was still ambiguous"
            }
            assertEquals(
                "two people share this name and neither was chosen, so no company is the answer yet: " +
                    "\"${record.answer.take(160)}\"\n  " + violations.joinToString("\n  "),
                emptyList<String>(), violations,
            )
        } finally {
            harness.close()
        }
    }

    // ---- 7.7 a historical answer is not a fresh verification ---------------------------------------

    @Test
    fun `a historical answer does not stand in for fresh verification before a side effect`() =
        runBlocking {
            val harness = harness()
            try {
                harness.turn("${hana.name} 명함 찾아줘.")
                harness.turn("그분 회사가 어디야?")
                val recall = harness.turn(contactRecallPhrasings().first())
                observe("recall_before_compose", recall, harness)

                val compose = harness.turn("그분에게 제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.")
                observe("compose_after_recall", compose, harness)

                val d = deltas(compose)
                val violations = mutableListOf<String>()
                if (compose.newComposeDrafts.isNotEmpty() && (d["get_contact"] ?: 0) < 1) {
                    violations += "opened a compose without reading the card first"
                }
                val order = compose.executedTools
                val getIndex = order.indexOf("get_contact")
                val composeIndex = order.indexOf("open_compose")
                if (composeIndex >= 0 && (getIndex < 0 || getIndex > composeIndex)) {
                    violations += "opened the compose before the card read: $order"
                }
                compose.newComposeDrafts.forEach { draft ->
                    if (draft.to.isBlank()) violations += "composed to nobody"
                }
                assertEquals(
                    "answering from memory establishes what was said, not that the address is still " +
                        "right. tools were $order\n  " + violations.joinToString("\n  "),
                    emptyList<String>(), violations,
                )
            } finally {
                harness.close()
            }
        }

    @Test
    fun `a failed fresh verification opens no compose`() = runBlocking {
        // The other half of the same rule: when the card cannot be re-read, nothing is sent.
        val harness = MultiturnScenarioHarness(cards = listOf(hana), searchFailure = true)
        try {
            val search = harness.turn("${hana.name} 명함 찾아줘.")
            val compose = harness.turn("그분에게 제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.")
            observe("compose_without_verified_target", compose, harness,
                note = "search failed: ${search.isError}")

            assertTrue(
                "with no verified target nothing may be composed: ${compose.newComposeDrafts.map { it.to }}",
                compose.newComposeDrafts.isEmpty(),
            )
        } finally {
            harness.close()
        }
    }

    // ---- evidence ------------------------------------------------------------------------------------

    companion object {
        private val OBSERVATIONS = mutableListOf<String>()

        private fun quote(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\""

        /** Writes what each turn actually did, whatever the assertions decided. */
        @AfterClass
        @JvmStatic
        fun writeObservations() {
            try {
                val directory = File("build/historical-recall-observations")
                if (!directory.isDirectory && !directory.mkdirs()) return
                File(directory, "turn_observations.json").writeText(
                    "{\n  \"what_this_is\": \"one entry per observed turn: the router's act, that " +
                        "turn's tool delta, and the answer. Counts are per turn, not cumulative.\",\n" +
                        "  \"turns\": [\n" + OBSERVATIONS.joinToString(",\n") + "\n  ]\n}\n",
                )
            } catch (ignored: Exception) {
                // Bookkeeping must never mask a contract failure.
            }
        }
    }
}
