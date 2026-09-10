package com.example.hjp.v4char

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.DialogueAct
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.2 — "show me this contact" is one intent, however it is worded.
 *
 * The defect this characterises is a closed verb list: 띄워줘 was not in it, so "봉예람 연락처 좀
 * 띄워줘" ran no lookup at all, the previous person stayed in focus, and the following anaphor
 * addressed a mail to them. The fix cannot be "add 띄워" — the next phrasing would fail the same way.
 * So the contract here is deliberately stated over a set of wordings that were never enumerated
 * anywhere in production, plus the separations that keep the rule from over-firing.
 *
 * Four distinctions must survive:
 *
 *  - one contact vs. the whole list;
 *  - a card read vs. a question about one field of a card already in focus;
 *  - an actual request now vs. a quotation, a recollection, a negation, a hypothetical, a capability
 *    question or an example;
 *  - a request whatever the order of target and predicate.
 */
class V4ReadIntentCharacterizationTest {

    private val target = V4Cards.SECOND

    private fun harness(vararg cards: BusinessCardRecord) =
        MultiturnScenarioHarness(cards = cards.toList())

    private suspend fun act(h: MultiturnScenarioHarness, text: String): DialogueAct =
        com.hjp.agent.core.DeterministicTurnRouter.act(
            h.session().turnContext(
                text.trim(), h.toolNames, null,
                h.directory.resolve(com.hjp.agent.core.ContactNameCandidates.candidates(text.trim())),
            ),
        )

    /** Wordings that all mean "go and put this person's card in front of me". */
    private val SINGLE_CONTACT_READS = listOf(
        "${target.name} 명함 찾아줘.",
        "${target.name} 명함 보여줘.",
        "${target.name} 연락처 좀 띄워줘.",
        "${target.name} 명함 열어줘.",
        "${target.name} 연락처 조회해줘.",
        "${target.name} 명함 확인해줘.",
        "${target.name} 명함 찾아주시겠어요?",
        "${target.name} 연락처 좀 띄워 줄래?",
        "${target.name} 연락처를 한번 확인하고 싶어.",
        "${target.name} 명함 좀 불러와 줘.",
        "${target.name} 명함 출력해줘.",
        // predicate first, target second
        "명함 좀 보여줘, ${target.name} 걸로.",
        "연락처 띄워줘 ${target.name}.",
    )

    private val LIST_READS = listOf(
        "연락처 목록 보여줘.",
        "명함 전체 보여줘.",
        "저장된 명함 다 보여줘.",
        "연락처 리스트업 해줘.",
    )

    // ---- positive -------------------------------------------------------------------------------

    @Test
    fun `every single-contact read wording actually reaches the store`() = runBlocking {
        SINGLE_CONTACT_READS.forEach { text ->
            val h = harness(V4Cards.ANCHOR, target)
            val turn = h.turn(text)
            assertTrue(
                "no contact read ran for: $text (tools=${turn.executedTools})",
                turn.executedTools.any { it == "search_contacts" || it == "get_contact" },
            )
            assertEquals(
                "the selected contact is wrong for: $text",
                target.id, turn.memory.selectedContact?.cardId,
            )
            h.close()
        }
    }

    @Test
    fun `a read that fails still retires the previous focus`() = runBlocking {
        SINGLE_CONTACT_READS.forEach { text ->
            // Only the anchor is in the store, so every one of these lookups must fail to find
            // the named person — and must not leave the anchor actionable.
            val h = harness(V4Cards.ANCHOR)
            h.turn("${V4Cards.ANCHOR.name} 명함 찾아줘.")
            h.turn(text)

            val follow = h.turn("그분에게 ${V4Phrasing.MAIL_REQUESTS.first()}")
            assertEquals(
                "after a failed read of '$text' the anchor was still reachable",
                0, follow.newComposeDrafts.size,
            )
            V4Assert.assertNeverContacted(h, V4Cards.ANCHOR.email, V4Cards.ANCHOR.mobile)
            h.close()
        }
    }

    // ---- the separations ------------------------------------------------------------------------

    @Test
    fun `a list request is not a single-contact search`() = runBlocking {
        LIST_READS.forEach { text ->
            val h = harness(V4Cards.ANCHOR, target)
            val observed = act(h, text)
            assertTrue(
                "$text was classified as $observed",
                observed == DialogueAct.CONTACT_SEARCH || observed == DialogueAct.CONTACT_DETAIL ||
                    observed == DialogueAct.GENERAL_INFORMATION,
            )
            // Whatever it routes to, it must not silently select one arbitrary person.
            val turn = h.turn(text)
            assertTrue(
                "$text selected a single contact out of a list request",
                turn.memory.selectedContact == null || turn.memory.candidateContacts.size > 1,
            )
            h.close()
        }
    }

    @Test
    fun `a field question about the person in focus is answered without a new search`() = runBlocking {
        val h = harness(V4Cards.ANCHOR)
        h.turn("${V4Cards.ANCHOR.name} 명함 찾아줘.")

        listOf("회사가 어디야?", "그 사람 회사 어디였지?", "직함이 뭐야?").forEach { question ->
            val turn = h.turn(question)
            assertTrue(
                "$question ran a fresh store search instead of reading the verified card",
                turn.executedTools.none { it == "search_contacts" },
            )
        }
        h.close()
    }

    @Test
    fun `quoted recalled negated hypothetical and capability wordings are not lookups`() = runBlocking {
        val h = harness(V4Cards.ANCHOR, target)
        val notRequests = listOf(
            "아까 '${target.name} 명함 띄워줘'라고 했었나?",
            "내가 ${target.name} 연락처 보여달라고 했었나요?",
            "${target.name} 명함은 찾지 마.",
            "만약 ${target.name} 명함을 찾으면 어떻게 돼?",
            "너는 명함을 띄워줄 수 있어?",
            "예를 들어 '${target.name} 연락처 보여줘' 같은 요청도 되나요?",
            "동료가 ${target.name} 명함 좀 띄워달라고 하던데.",
        )
        notRequests.forEach { text ->
            val turn = h.turn(text)
            assertTrue(
                "$text was executed as a real lookup (tools=${turn.executedTools})",
                turn.executedTools.none { it == "search_contacts" },
            )
        }
        h.close()
    }
}
