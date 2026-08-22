package com.example.hjp.integration

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.TrackedAction
import com.hjp.agent.contract.TrackedActionStatus
import com.hjp.agent.core.ConversationHistoryStrategy
import com.hjp.tool.contact.BusinessCardRecord
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What actually arrives at the model, section by section.
 *
 * [StructuredMemoryCharacterizationTest] pins what the session *remembers*. This file pins what the
 * model is *shown*, which is a different question and is checked differently: every assertion here
 * reads one recorded model request — the string the production kernel hands to the gateway, built by
 * the production [com.hjp.agent.core.ModelContextSelector] — and looks at the section it landed in.
 *
 * ## Why the section matters
 *
 * "The value appears somewhere in the prompt" is not evidence that memory projected it. A user who
 * says "내 회사는 X이야" puts X into the conversation, and the conversation is replayed back to the
 * model as history for several turns afterwards. A test that searches the concatenation of every
 * prompt for X therefore passes whether or not the fact was ever projected at all — it is measuring
 * the user's own sentence.
 *
 * So each check below splits one rendered request into its `[section]` blocks and asks the question
 * against the right one:
 *
 *  - **memory projection** — everything the selector states as verified context. A remembered fact
 *    has to be here, because this is the part that survives after the sentence scrolls out of
 *    history.
 *  - **history** — `relevant_history`, `recent_conversation`, `history_digest`. The user's own words,
 *    replayed. Finding a value here proves nothing about memory.
 *  - **current_user** — the request being made now, and nothing else.
 *
 * ## Sentinels
 *
 * Every value is an invented token that appears nowhere else in the fixture, the tool catalog or the
 * system instruction, so a match is never a coincidence and an occurrence count means what it says.
 *
 * ## This is a characterization
 *
 * It is written before any fix and describes current behaviour. Failures here are the finding.
 */
class ModelContextMemoryProjectionCharacterizationTest {

    private val person = BusinessCardRecord(
        id = "P001", name = "표하윤", company = "너울건설", title = "안전관리",
        industry = "건설", email = "hayun@neoul.example.net", mobile = "010-6060-0001",
    )

    // ---- sentinels: invented, unique, and impossible to reach by accident ------------------------

    private val companyA = "큐리악스제타"
    private val companyB = "델타보름스"
    private val titleValue = "라온책임"
    private val preferenceValue = "누리말투"
    private val constraintValue = "하마루확인"
    private val oldTalk = "예벌하늘"
    private val currentTalk = "미르바람"

    private fun harness(
        searchFailure: Boolean = false,
        confirmUpdates: Boolean = true,
        clockMillis: (() -> Long)? = null,
    ) = MultiturnScenarioHarness(
        cards = listOf(person),
        searchFailure = searchFailure,
        confirmUpdates = confirmUpdates,
        sessionClockMillis = clockMillis,
    )

    // ---- reading one model request ---------------------------------------------------------------

    /** One rendered model request, split into the sections the selector emitted. */
    private class ModelRequestView(val raw: String, val sections: Map<String, String>) {
        val currentUser: String get() = sections[CURRENT_USER].orEmpty()

        /** The user's own words, replayed as context. */
        val history: String
            get() = sections.filterKeys { it in HISTORY_SECTIONS }.values.joinToString("\n")

        /**
         * What the agent states as verified context — everything that is neither replayed
         * conversation nor the live request. A remembered fact has to survive here.
         */
        val memoryProjection: String
            get() = sections
                .filterKeys { it !in HISTORY_SECTIONS && it != CURRENT_USER }
                .values.joinToString("\n")

        fun sectionOf(needle: String): List<String> =
            sections.filterValues { it.contains(needle) }.keys.toList()

        fun occurrences(needle: String): Int =
            Regex(Regex.escape(needle)).findAll(raw).count()

        override fun toString(): String =
            sections.entries.joinToString("\n") { "[${it.key}]\n${it.value}" }

        companion object {
            const val CURRENT_USER = "current_user"
            val HISTORY_SECTIONS = setOf("relevant_history", "recent_conversation", "history_digest")
            private val HEADER = Regex("^\\[([a-z_]+)]$", RegexOption.MULTILINE)

            fun parse(prompt: String): ModelRequestView {
                val headers = HEADER.findAll(prompt).toList()
                // A first turn carries no sections at all: the request is the whole prompt.
                if (headers.isEmpty()) {
                    return ModelRequestView(prompt, mapOf(CURRENT_USER to prompt))
                }
                val sections = LinkedHashMap<String, String>()
                headers.forEachIndexed { index, header ->
                    val bodyStart = (header.range.last + 2).coerceAtMost(prompt.length)
                    val bodyEnd = if (index + 1 < headers.size) headers[index + 1].range.first
                    else prompt.length
                    sections[header.groupValues[1]] = prompt.substring(bodyStart, bodyEnd).trim()
                }
                return ModelRequestView(prompt, sections)
            }
        }
    }

    /**
     * Runs a turn and returns the single model request it produced.
     *
     * Taking the request this turn produced, rather than the concatenation of every request so far,
     * is the whole point: a projection claim has to hold for one call to the model.
     */
    private suspend fun MultiturnScenarioHarness.request(text: String): ModelRequestView {
        val before = gateway.prompts.size
        turn(text)
        val produced = gateway.prompts.drop(before)
        assertEquals(
            "a turn must produce exactly one model request for this reading to mean anything",
            1, produced.size,
        )
        return ModelRequestView.parse(produced.single())
    }

    // ---- the fixture itself -----------------------------------------------------------------------

    @Test
    fun `the session really does store the fact these checks are about`() = runBlocking {
        // Separates two very different failures: memory never recorded the fact, or memory recorded
        // it and the projection dropped it. Only the second is what the rest of this file is about.
        val harness = harness()
        try {
            val record = harness.turn("내 회사는 ${companyA}이야.")
            assertTrue(
                "the fact must be in memory before asking whether it reaches the model: " +
                    "${record.memory.confirmedFacts}",
                record.memory.confirmedFacts.any { it.content.contains(companyA) },
            )
            assertTrue(
                "and it must carry the key that makes replacement possible: " +
                    "${record.memory.confirmedFacts.map { it.key }}",
                record.memory.confirmedFacts.any { it.key == "user.company" },
            )
        } finally {
            harness.close()
        }
    }

    // ---- 6.1 a confirmed fact reaches the model as memory ------------------------------------------

    @Test
    fun `a stated fact is projected into the model request, not merely echoed as history`() =
        runBlocking {
            val harness = harness()
            try {
                harness.turn("내 회사는 ${companyA}이야.")
                // An unrelated request. It shares no term with the sentence above, so nothing pulls
                // that turn back as relevant history for its own sake.
                val view = harness.request("${person.name} 명함 찾아줘.")

                assertTrue(
                    "the fact is in memory, so it must be in the part of the request that states " +
                        "verified context — not only in the replayed sentence that first said it. " +
                        "found in sections ${view.sectionOf(companyA)}; projection was:\n" +
                        view.memoryProjection,
                    view.memoryProjection.contains(companyA),
                )
            } finally {
                harness.close()
            }
        }

    @Test
    fun `a fact still reaches the model once its sentence has left the recent window`() = runBlocking {
        // The reason a fact is remembered at all: the sentence does not stay in view forever.
        val harness = harness()
        try {
            harness.turn("내 회사는 ${companyA}이야.")
            repeat(5) { index -> harness.turn("$oldTalk$index 관련해서 그냥 잡담이에요.") }
            val view = harness.request("고맙습니다.")

            assertFalse(
                "the fixture only tests something if the original sentence has scrolled out of the " +
                    "recent window: ${view.sections["recent_conversation"]}",
                view.sections["recent_conversation"].orEmpty().contains(companyA),
            )
            assertTrue(
                "and the fact must survive that, which is what memory is for. found in sections " +
                    "${view.sectionOf(companyA)}; projection was:\n${view.memoryProjection}",
                view.memoryProjection.contains(companyA),
            )
        } finally {
            harness.close()
        }
    }

    // ---- 6.2 a keyed fact replaces rather than accumulates -----------------------------------------

    @Test
    fun `a replaced fact projects the new value`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("내 회사는 ${companyA}이야.")
            harness.turn("내 회사는 ${companyB}야.")
            val view = harness.request("${person.name} 명함 찾아줘.")

            assertTrue(
                "the current value of the key must be what the model is told. found in sections " +
                    "${view.sectionOf(companyB)}; projection was:\n${view.memoryProjection}",
                view.memoryProjection.contains(companyB),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a superseded fact is not projected`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("내 회사는 ${companyA}이야.")
            harness.turn("내 회사는 ${companyB}야.")
            val view = harness.request("${person.name} 명함 찾아줘.")

            // Only the projection is asked about. The old sentence staying visible in replayed
            // history is the conversation, not a stale fact being asserted as true.
            assertFalse(
                "a superseded value must not be stated as current context. found in sections " +
                    "${view.sectionOf(companyA)}; projection was:\n${view.memoryProjection}",
                view.memoryProjection.contains(companyA),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `one key is projected once and a different key survives beside it`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("내 회사는 ${companyA}이야.")
            harness.turn("내 직책은 ${titleValue}이야.")
            harness.turn("내 회사는 ${companyB}야.")
            val view = harness.request("${person.name} 명함 찾아줘.")

            assertEquals(
                "the replaced key must be stated exactly once, not twice with different values: " +
                    view.memoryProjection,
                1,
                Regex(Regex.escape(companyB)).findAll(view.memoryProjection).count(),
            )
            assertTrue(
                "a different key must not be lost when another one is replaced: " +
                    view.memoryProjection,
                view.memoryProjection.contains(titleValue),
            )
        } finally {
            harness.close()
        }
    }

    // ---- 6.3 preferences, constraints and recent dialogue --------------------------------------------

    @Test
    fun `an active preference reaches the model`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("앞으로는 ${preferenceValue} 스타일로 답해줘.")
            val record = harness.turn("${person.name} 명함 찾아줘.")
            assertTrue(
                "the fixture must actually have recorded a preference: ${record.memory.preferences}",
                record.memory.preferences.any { it.content.contains(preferenceValue) },
            )
            val view = harness.request("고맙습니다.")

            assertTrue(
                "a standing preference is context the model needs stated, not history to infer " +
                    "from. found in sections ${view.sectionOf(preferenceValue)}; projection was:\n" +
                    view.memoryProjection,
                view.memoryProjection.contains(preferenceValue),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `an active constraint reaches the model`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("${constraintValue} 없이는 반드시 먼저 물어봐줘.")
            val record = harness.turn("${person.name} 명함 찾아줘.")
            assertTrue(
                "the fixture must actually have recorded a constraint: ${record.memory.constraints}",
                record.memory.constraints.any { it.content.contains(constraintValue) },
            )
            val view = harness.request("고맙습니다.")

            assertTrue(
                "a standing constraint must be stated, since violating it is the failure it exists " +
                    "to prevent. found in sections ${view.sectionOf(constraintValue)}; " +
                    "projection was:\n${view.memoryProjection}",
                view.memoryProjection.contains(constraintValue),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `the replayed window is bounded whenever history is sent at all`() = runBlocking {
        // Exercised through the strategy that bootstraps on every request, because that is the only
        // way to observe the window itself. Under the shipped strategy the same code runs once, when
        // a native conversation starts or rotates — see the test below for what the shipped
        // strategy does on every other turn.
        val harness = MultiturnScenarioHarness(
            cards = listOf(person),
            strategy = ConversationHistoryStrategy.DUPLICATE_BASELINE,
        )
        try {
            harness.turn("$oldTalk 이야기부터 시작할게요.")
            repeat(5) { index -> harness.turn("중간 잡담 $index 입니다.") }
            val view = harness.request("고맙습니다.")

            val recent = view.sections["recent_conversation"].orEmpty()
            assertTrue("a bootstrapping request must carry the recent window: $view", recent.isNotBlank())
            val replayedTurns = Regex("^turn \\d+$", RegexOption.MULTILINE).findAll(recent).count()
            assertTrue(
                "the window is bounded, not the whole transcript: $replayedTurns turns",
                replayedTurns in 1..4,
            )
            assertFalse(
                "a turn well outside the window must not be inside it: $recent",
                recent.contains(oldTalk),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `the shipped strategy stops resending history once the native conversation holds it`() =
        runBlocking {
            // Not a defect — the stated policy. The native conversation re-renders every stored turn
            // on each send, so resending the same turns inside the user message would duplicate
            // them. It is recorded here because it decides where a remembered fact has to live: with
            // no history section on an ordinary turn, anything memory is meant to carry must be in
            // the state projection or it reaches the model not at all.
            val harness = harness()
            try {
                harness.turn("$oldTalk 이야기부터 시작할게요.")
                repeat(3) { index -> harness.turn("중간 잡담 $index 입니다.") }
                val view = harness.request("고맙습니다.")

                assertEquals(
                    "after the first turn the app sends state and the live request, nothing else: $view",
                    listOf("session_state", "current_user"), view.sections.keys.toList(),
                )
                assertTrue(
                    "so there is no replayed history on this request at all",
                    view.history.isEmpty(),
                )
            } finally {
                harness.close()
            }
        }

    // ---- 6.4 the live request appears once ------------------------------------------------------------

    @Test
    fun `the current user turn appears exactly once in the request`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("${person.name} 명함 찾아줘.")
            val view = harness.request("$currentTalk 라고 메모해두면 좋겠네요.")

            assertEquals(
                "the live request belongs in [current_user] and nowhere else. found in sections " +
                    "${view.sectionOf(currentTalk)}:\n$view",
                1, view.occurrences(currentTalk),
            )
            assertTrue(
                "and that one occurrence must be the request itself",
                view.currentUser.contains(currentTalk),
            )
            assertFalse(
                "the turn under way is not history yet",
                view.history.contains(currentTalk),
            )
        } finally {
            harness.close()
        }
    }

    // ---- 6.5 internal action bookkeeping stays internal --------------------------------------------

    /**
     * The strings that exist only because the agent keeps an audit trail.
     *
     * Derived from the actions the session actually recorded rather than from a fixed list, so the
     * check follows the data instead of a guess. [TrackedAction.request] is deliberately excluded:
     * it is the user's own sentence, and seeing it replayed as conversation is not a leak.
     */
    private fun internalMarkers(memory: ConversationMemory): List<Pair<String, String>> {
        val markers = mutableListOf<Pair<String, String>>()
        memory.actions.forEach { action ->
            markers += action.status.name to "action status"
            markers += action.turnId to "internal turn id"
            action.executedTools.forEach { markers += it to "executed tool name" }
            action.detailKo?.takeIf { it.length >= 8 }?.let { markers += it to "detailKo" }
            action.outcomeType?.let { markers += it.name to "internal outcome type" }
        }
        listOf("open_actions", "closed_actions", "actions:", "executed_tools", "detail_ko")
            .forEach { markers += it to "action ledger label" }
        return markers.distinctBy { it.first }
    }

    private fun assertNoInternalActionMetadata(
        situation: String,
        view: ModelRequestView,
        memory: ConversationMemory,
    ) {
        val leaked = internalMarkers(memory).mapNotNull { (marker, kind) ->
            val sections = view.sectionOf(marker)
            if (sections.isEmpty()) null else "$kind \"$marker\" in $sections"
        }
        assertEquals(
            "$situation — the model is told facts about the user, not the agent's own bookkeeping:\n" +
                view.toString(),
            emptyList<String>(), leaked,
        )
    }

    @Test
    fun `a completed action leaks no internal metadata`() = runBlocking {
        val harness = harness()
        try {
            val done = harness.turn("${person.name} 명함 찾아줘.")
            assertTrue(
                "the fixture must have produced a closed action: ${done.memory.actions.map { it.status }}",
                done.memory.actions.any { it.status == TrackedActionStatus.COMPLETED },
            )
            val view = harness.request("고맙습니다.")
            assertNoInternalActionMetadata("COMPLETED", view, harness.session().conversationMemory)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `an open action leaks no internal metadata`() = runBlocking {
        val harness = harness()
        try {
            val open = harness.turn("제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.")
            assertTrue(
                "the fixture must have left something open: ${open.memory.actions.map { it.status }}",
                open.memory.openActions.isNotEmpty(),
            )
            val view = harness.request("고맙습니다.")
            assertNoInternalActionMetadata("open", view, harness.session().conversationMemory)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a failed action leaks no internal metadata`() = runBlocking {
        val harness = harness(searchFailure = true)
        try {
            val failed = harness.turn("${person.name} 명함 찾아줘.")
            assertTrue(
                "the fixture must have produced a failure: ${failed.memory.actions.map { it.status }}",
                failed.memory.actions.any { it.status == TrackedActionStatus.FAILED },
            )
            val view = harness.request("고맙습니다.")
            assertNoInternalActionMetadata("FAILED", view, harness.session().conversationMemory)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a declined action leaks no internal metadata`() = runBlocking {
        val harness = harness(confirmUpdates = false)
        try {
            harness.turn("${person.name} 명함 찾아줘.")
            val declined = harness.turn("${person.name} 회사를 ${companyB}로 바꿔줘.")
            val statuses = declined.memory.actions.map { it.status }
            assertTrue(
                "the fixture must have produced a non-completed outcome for a declined edit: $statuses",
                statuses.any { it != TrackedActionStatus.COMPLETED },
            )
            val view = harness.request("고맙습니다.")
            assertNoInternalActionMetadata("declined", view, harness.session().conversationMemory)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `an expired action leaks no internal metadata`() = runBlocking {
        val clock = ManualClock(1_700_000_000_000)
        val harness = harness(clockMillis = clock.millis)
        try {
            harness.turn("제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.")
            clock.advance(THIRTY_MINUTES + 1)
            val aged = harness.turn("고맙습니다.")
            assertTrue(
                "the fixture must have aged something out: ${aged.memory.actions.map { it.status }}",
                aged.memory.actions.any { it.status == TrackedActionStatus.EXPIRED },
            )
            val view = harness.request("잘 부탁드립니다.")
            assertNoInternalActionMetadata("EXPIRED", view, harness.session().conversationMemory)
        } finally {
            harness.close()
        }
    }

    // ---- 6.6 keeping it out of the prompt must not remove it from the session -----------------------

    @Test
    fun `the action ledger survives being kept out of the model request`() = runBlocking {
        val harness = harness()
        try {
            val opened = harness.turn("${person.name} 명함 찾아줘.")
            val tracked = opened.memory.actions.single { it.request.contains(person.name) }

            harness.request("고맙습니다.")
            val after = harness.session().conversationMemory

            val same = after.actions.singleOrNull { it.turnId == tracked.turnId }
            assertTrue("the record must still be there after a model request: ${after.actions}", same != null)
            assertEquals("its status must not have been changed by rendering a prompt",
                tracked.status, same!!.status)
            assertEquals("nor its tool trace", tracked.executedTools, same.executedTools)
            assertEquals("nor its timestamp", tracked.updatedAtEpochMillis, same.updatedAtEpochMillis)
            assertTrue(
                "and the agent's own view of what it was asked to do must remain readable",
                after.actionMemory.isNotEmpty(),
            )
        } finally {
            harness.close()
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private class ManualClock(startMillis: Long) {
        private val now = AtomicLong(startMillis)
        val millis: () -> Long = { now.get() }
        fun advance(byMillis: Long) = now.addAndGet(byMillis)
    }

    private companion object {
        const val THIRTY_MINUTES = 30L * 60 * 1_000
    }
}
