package com.example.hjp.integration

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.TrackedActionStatus
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the session remembers, and what the model is allowed to see.
 *
 * Three things are being separated here, and today they are not separated at all:
 *
 *  - **What the user asked the agent to do.** Every turn currently becomes a `TrackedAction`, so
 *    "오늘 날씨가 좋네" is recorded as a pending request the agent owes an answer to. The router and
 *    the failure-question path read that list, so filling it with small talk is not cosmetic.
 *  - **What the model is told.** `session_state` renders `open_actions`, `closed_actions` and the
 *    tools each one executed. The model therefore sees an internal audit trail — statuses, request
 *    text, tool names — none of which is a fact about the user.
 *  - **What is true now.** Facts are de-duplicated by their exact text, so "내 회사는 비전글로벌이야"
 *    followed by "내 회사는 새길테크야" leaves *both* in memory. Nothing marks the first as stale, and
 *    both can reach the model at once.
 *
 * The upstream structured-memory branch shares all three: its reducer records every utterance as a
 * pending action, de-duplicates on `content`, and has no expiry. So this is not a port — it is the
 * contract the port was supposed to deliver, written as tests first.
 *
 * ## One deviation from upstream, and why
 *
 * Upstream keeps `pendingActions` and `resolvedActions` as separate lists. Here a single
 * `TrackedAction` list carries every turn, because that type is also where a turn's typed outcome
 * lives — dropping the record would drop the outcome with it. So "action memory" is the *subset*
 * that asked for something: [ConversationMemory.actionMemory]. Small talk still leaves a turn record
 * and carries no request.
 */
class StructuredMemoryCharacterizationTest {

    private val person = BusinessCardRecord(
        id = "M001", name = "표하윤", company = "너울건설", title = "안전관리",
        industry = "건설", email = "hayun@neoul.example.net", mobile = "010-6060-0001",
    )

    private fun harness() = MultiturnScenarioHarness(cards = listOf(person))

    // ---- 4.1 general conversation must not become an action ------------------------------------------

    /** Utterances that ask the agent to do nothing. */
    private val smallTalk = listOf(
        "오늘 날씨가 좋네." to "an observation",
        "안녕하세요." to "a greeting",
        "고맙습니다." to "thanks",
        "정말 도움이 됐어요." to "an appreciation",
        "음, 그렇군요." to "an acknowledgement",
        "명함이라는 게 원래 언제부터 쓰였나요?" to "a general-knowledge question",
    )

    /** Utterances that report or hypothesise about an action without requesting one. */
    private val nonRequests = listOf(
        "제가 '메일 보내줘'라고 했었나요?" to "a quoted request being recalled",
        "아까 문자 보내달라고 한 적 없어요." to "a negation",
        "만약 메일을 보낸다면 어떻게 되나요?" to "a hypothetical",
        "예를 들어 '일정 잡아줘' 같은 문장이요." to "an example",
    )

    @Test
    fun `small talk does not become a tracked action`() = runBlocking {
        val polluting = mutableListOf<String>()
        smallTalk.forEach { (text, kind) ->
            val harness = harness()
            try {
                val record = harness.turn(text)
                val actions = record.memory.actionMemory
                if (actions.isNotEmpty()) polluting += "$kind: \"$text\" -> ${actions.map { it.status }}"
            } finally {
                harness.close()
            }
        }
        assertEquals(
            "an utterance that asks for nothing is not a request the agent owes an answer to; the " +
                "router and the failure-question path read this list",
            emptyList<String>(),
            polluting,
        )
    }

    @Test
    fun `reported, negated, hypothetical and example speech does not become a tracked action`() =
        runBlocking {
            val polluting = mutableListOf<String>()
            nonRequests.forEach { (text, kind) ->
                val harness = harness()
                try {
                    val record = harness.turn(text)
                    if (record.memory.actionMemory.isNotEmpty()) {
                        polluting += "$kind: \"$text\" -> ${record.memory.actionMemory.map { it.request }}"
                    }
                } finally {
                    harness.close()
                }
            }
            assertEquals(
                "talking about an action is not requesting one",
                emptyList<String>(),
                polluting,
            )
        }

    @Test
    fun `real requests are recorded`() = runBlocking {
        // The mirror assertion. A rule that records nothing would pass the two tests above.
        val requests = listOf(
            "${person.name} 명함 찾아줘.",
            "${person.name}에게 제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.",
            "${person.name}과 2027년 3월 4일 오후 2시에 회의 잡아줘.",
            "${person.name} 명함 메모를 우선연락으로 수정해줘.",
        )
        val missing = mutableListOf<String>()
        requests.forEach { text ->
            val harness = harness()
            try {
                val record = harness.turn(text)
                if (record.memory.actionMemory.isEmpty()) missing += text
            } finally {
                harness.close()
            }
        }
        assertEquals("a real request must be tracked", emptyList<String>(), missing)
    }

    @Test
    fun `the rule is about the shape of the request, not a list of sentences`() = runBlocking {
        // Paraphrases of the same two categories, none of which appears above.
        val stillSmallTalk = listOf("점심 뭐 드셨어요?", "오랜만이네요.", "수고 많으셨습니다.")
        val stillRequests = listOf("표하윤 연락처 좀 띄워줘.", "표하윤 씨 명함 조회해 주세요.")

        val wrong = mutableListOf<String>()
        stillSmallTalk.forEach { text ->
            val harness = harness()
            try {
                if (harness.turn(text).memory.actionMemory.isNotEmpty()) wrong += "recorded small talk: $text"
            } finally { harness.close() }
        }
        stillRequests.forEach { text ->
            val harness = harness()
            try {
                if (harness.turn(text).memory.actionMemory.isEmpty()) wrong += "dropped a request: $text"
            } finally { harness.close() }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    // ---- 4.2 the model must not be shown the action ledger -------------------------------------------

    @Test
    fun `no prompt section carries an action status, request or tool trace`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("${person.name} 명함 찾아줘.")
            harness.turn("그 사람에게 제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.")
            harness.turn("오늘 날씨가 좋네.")

            val leaked = mutableListOf<String>()
            harness.gateway.prompts.forEachIndexed { index, prompt ->
                listOf(
                    "open_actions", "closed_actions",
                    TrackedActionStatus.PENDING.name, TrackedActionStatus.COMPLETED.name,
                    TrackedActionStatus.FAILED.name, TrackedActionStatus.CANCELLED.name,
                    "search_contacts", "get_contact", "open_compose",
                    "도구 실행 완료",
                ).forEach { marker ->
                    if (prompt.contains(marker)) leaked += "prompt $index contains \"$marker\""
                }
            }
            assertEquals(
                "the model is told facts about the user, not the agent's own audit trail. Statuses, " +
                    "request text and executed tool names are internal state.",
                emptyList<String>(),
                leaked,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `the internal action ledger is still available to the agent`() = runBlocking {
        // Removing the leak must not remove the state. The deterministic router and the
        // "왜 실패했어?" path both read it.
        val harness = harness()
        try {
            val record = harness.turn("${person.name} 명함 찾아줘.")
            assertTrue(
                "the agent still tracks what it was asked to do",
                record.memory.actionMemory.isNotEmpty(),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `facts, preferences and constraints do reach the model`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("내 회사는 비전글로벌이야.")
            harness.turn("앞으로는 존댓말로 답해줘.")
            harness.turn("${person.name} 명함 찾아줘.")

            val prompts = harness.gateway.prompts.joinToString("\n")
            assertTrue(
                "a stated fact about the user is exactly what belongs in the prompt: $prompts",
                prompts.contains("비전글로벌"),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `the current user turn is not inserted twice`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("${person.name} 명함 찾아줘.")
            val prompt = harness.gateway.prompts.last()
            val occurrences = Regex(Regex.escape("${person.name} 명함 찾아줘.")).findAll(prompt).count()
            assertTrue(
                "the turn under way must appear once, not once as history and once as the request: " +
                    "$occurrences occurrences",
                occurrences <= 1,
            )
        } finally {
            harness.close()
        }
    }

    // ---- 4.3 a fact replaces the older value for the same key ----------------------------------------

    @Test
    fun `restating a fact replaces it rather than accumulating`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("내 회사는 비전글로벌이야.")
            val record = harness.turn("내 회사는 새길테크야.")

            val companyFacts = record.memory.confirmedFacts.filter { it.content.contains("회사") }
            assertEquals(
                "one key, one value: $companyFacts",
                1,
                companyFacts.size,
            )
            assertTrue(
                "the surviving value is the newer one: ${companyFacts.map { it.content }}",
                companyFacts.single().content.contains("새길테크"),
            )
            assertTrue(
                "and the superseded value must not still be in memory",
                record.memory.confirmedFacts.none { it.content.contains("비전글로벌") },
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a superseded fact never reaches the model`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("내 회사는 비전글로벌이야.")
            harness.turn("내 회사는 새길테크야.")
            harness.turn("${person.name} 명함 찾아줘.")

            val prompt = harness.gateway.prompts.last()
            assertTrue(
                "a stale value alongside the current one is worse than no value: $prompt",
                !prompt.contains("비전글로벌"),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `different keys do not overwrite each other`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("내 회사는 비전글로벌이야.")
            harness.turn("내 직책은 팀장이야.")
            val record = harness.turn("내 이름은 소정이야.")

            val facts = record.memory.confirmedFacts.map { it.content }
            assertTrue("the company survives: $facts", facts.any { it.contains("새길테크") || it.contains("비전글로벌") })
            assertTrue("the title survives: $facts", facts.any { it.contains("팀장") })
            assertTrue("the name survives: $facts", facts.any { it.contains("소정") })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `phrasing variants of the same key still replace`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("내 회사는 비전글로벌이야.")
            val record = harness.turn("아, 제 회사는 새길테크입니다.")

            val companyFacts = record.memory.confirmedFacts.filter { it.content.contains("회사") }
            assertEquals(
                "politeness and a correction marker do not make it a different fact: $companyFacts",
                1,
                companyFacts.size,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a replaced fact keeps its provenance fields`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("내 회사는 비전글로벌이야.")
            val record = harness.turn("내 회사는 새길테크야.")
            val fact = record.memory.confirmedFacts.first { it.content.contains("회사") }

            assertTrue("sourceTurnId must be set", fact.sourceTurnId.isNotBlank())
            assertTrue("updatedAt must be set", fact.updatedAtEpochMillis > 0)
        } finally {
            harness.close()
        }
    }

    // ---- 4.5 answering from memory versus looking something up ---------------------------------------

    @Test
    fun `a question about a remembered fact runs no tool`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("내 회사는 비전글로벌이야.")
            val record = harness.turn("아까 회사가 어디라고 했지?")

            assertEquals(
                "the answer is already in memory; searching the card store for it is wrong",
                emptyList<String>(),
                record.executedTools,
            )
            assertTrue(
                "and the remembered value must actually be answered: ${record.answer}",
                record.answer.contains("비전글로벌"),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `an explicit request for fresh information does look it up`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("${person.name} 명함 찾아줘.")
            val record = harness.turn("그 사람 최신 회사 정보를 다시 검색해줘.")

            assertTrue(
                "\"최신\" and \"다시 검색\" ask for a lookup, not a recollection: ${record.executedTools}",
                record.executedTools.isNotEmpty(),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a remembered fact is never used as a recipient`() = runBlocking {
        // Memory holds what the user said. It is not a contact record, and nothing in it may become
        // an address a message is sent to.
        val harness = harness()
        try {
            harness.turn("내 이메일은 sojung@example.net 이야.")
            val record = harness.turn("거기로 제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.")

            assertTrue(
                "a remembered address must not become a compose recipient: " +
                    "${record.newComposeDrafts.map { it.to }}",
                record.newComposeDrafts.none { it.to == "sojung@example.net" },
            )
        } finally {
            harness.close()
        }
    }
}
