package com.example.hjp.v4char

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.DialogueAct
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.4 — decide what kind of utterance this is before deciding whether the agent may do it.
 *
 * "지워달라고 했나?" is a question about the past. Running the capability veto first turned it into a
 * refusal to delete, which is an answer to a request nobody made. The order has to be:
 *
 *  1. is this quoted, reported, recalled, interrogative, hypothetical, negated, exemplary or a
 *     question about what the agent can do?
 *  2. only if none of those, is it a request to act now?
 *  3. only if it is a request to act now, does the capability veto or the tool routing apply.
 *
 * The negative direction matters as much: a real, present-tense delete or edit request must still hit
 * the veto or the ordinary policy. A fix that made every sentence "not a request" would satisfy half
 * of this file and break the agent.
 */
class V4DialogueActPriorityCharacterizationTest {

    private fun harness(vararg cards: BusinessCardRecord) =
        MultiturnScenarioHarness(cards = cards.toList())

    private suspend fun act(h: MultiturnScenarioHarness, text: String): DialogueAct =
        com.hjp.agent.core.DeterministicTurnRouter.act(
            h.session().turnContext(
                text.trim(), h.toolNames, null,
                h.directory.resolve(com.hjp.agent.core.ContactNameCandidates.candidates(text.trim())),
            ),
        )

    /** Acts that mean "the user asked me to carry something out". */
    private val REQUEST_ACTS = setOf(
        DialogueAct.ACTION_COMPOSE, DialogueAct.ACTION_CALENDAR, DialogueAct.ACTION_UPDATE,
    )

    /** Utterances about an action, not requests for one. */
    private val ABOUT_AN_ACTION = listOf(
        "지워달라고 했나?",
        "내가 명함 지워달라고 했었나요?",
        "아까 '메모 지워줘'라고 했잖아.",
        "그 메모는 지우지 마.",
        "만약 명함을 지우면 어떻게 돼?",
        "너 명함 지울 수 있어?",
        "예를 들어 '메모 지워줘' 같은 요청도 처리해?",
        "동료가 명함 좀 지워달라고 하던데.",
        "제가 메일 보내달라고 했었나요?",
        "일정 만들어달라고 한 적 있었나?",
    )

    /** The same verbs, as a genuine present-tense request. */
    private val REAL_REQUESTS = listOf(
        "그 사람 메모를 재검토로 수정해줘.",
        "그 사람 메모 지워줘.",
        "그분에게 ${V4Phrasing.MAIL_REQUESTS.first()}",
    )

    @Test
    fun `an utterance about an action is never executed as one`() = runBlocking {
        ABOUT_AN_ACTION.forEach { text ->
            val h = harness(V4Cards.ANCHOR)
            h.turn("${V4Cards.ANCHOR.name} 명함 찾아줘.")

            val observed = act(h, text)
            val turn = h.turn(text)

            assertTrue(
                "'$text' was classified as a request: $observed",
                observed !in REQUEST_ACTS,
            )
            assertTrue(
                "'$text' ran an action tool: ${turn.executedTools}",
                turn.executedTools.none { it in V4Assert.ACTION_TOOLS },
            )
            assertEquals("'$text' produced a side effect", 0,
                turn.newComposeDrafts.size + turn.newCalendarDrafts.size)
            h.close()
        }
    }

    /**
     * A recall question must be answered as a recall — not refused as an unsupported capability.
     *
     * This is the half the capability-first order got wrong: the user asked what they had said, and
     * the reply explained that deletion is not supported.
     */
    @Test
    fun `a question about the past is answered as a question about the past`() = runBlocking {
        listOf(
            "지워달라고 했나?",
            "내가 명함 지워달라고 했었나요?",
            "제가 메일 보내달라고 했었나요?",
        ).forEach { text ->
            val h = harness(V4Cards.ANCHOR)
            h.turn("${V4Cards.ANCHOR.name} 명함 찾아줘.")
            val observed = act(h, text)
            assertTrue(
                "'$text' was classified as $observed rather than a question about the conversation",
                observed == DialogueAct.QUOTED_RECALL || observed == DialogueAct.HISTORY_QUESTION ||
                    observed == DialogueAct.FAILURE_QUESTION,
            )
            h.close()
        }
    }

    /** The control: a present-tense request still routes as a request. */
    @Test
    fun `a genuine present-tense request still routes as one`() = runBlocking {
        REAL_REQUESTS.forEach { text ->
            val h = harness(V4Cards.ANCHOR)
            h.turn("${V4Cards.ANCHOR.name} 명함 찾아줘.")
            val observed = act(h, text)
            assertTrue(
                "'$text' stopped being a request: $observed",
                observed in REQUEST_ACTS || observed == DialogueAct.UNSUPPORTED,
            )
            h.close()
        }
    }
}
