package com.example.hjp.v4char

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.5 — a turn that asked the user for a missing value is a clarification, not general information.
 *
 * Typing it as GENERAL_INFORMATION made "I asked you what time you wanted" and "I said something
 * vague and ran nothing" the same recorded outcome, so a turn that did nothing scored as a success.
 * The contract is checked on all four surfaces at once: the typed outcome, the tool trace, the side
 * effects, and whether the reply actually names what is missing.
 */
class V4MissingSlotCharacterizationTest {

    private fun harness(vararg cards: BusinessCardRecord) =
        MultiturnScenarioHarness(cards = cards.toList())

    /** Requests missing exactly one required slot, and the word the answer must mention. */
    private data class Gap(val text: String, val mustMention: List<String>)

    private val CALENDAR_GAPS = listOf(
        Gap("일정 하나 만들어줘.", listOf("시각", "시간", "날짜", "언제")),
        Gap("협의 일정 좀 잡아줘.", listOf("시각", "시간", "날짜", "언제")),
        Gap("내일 일정 만들어줘.", listOf("시각", "시간", "몇 시", "언제")),
    )

    private val COMPOSE_GAPS = listOf(
        Gap("메일 좀 작성해줘.", listOf("누구", "받는", "수신")),
        Gap("문자 작성해줘.", listOf("누구", "받는", "수신")),
    )

    @Test
    fun `a calendar request with no time asks for the time and runs nothing`() = runBlocking {
        CALENDAR_GAPS.forEach { gap ->
            val h = harness(V4Cards.ANCHOR)
            val turn = h.turn(gap.text)

            assertEquals(
                "'${gap.text}' -> ${turn.outcomeType}",
                TurnOutcomeType.CLARIFICATION_REQUIRED, turn.outcomeType,
            )
            assertTrue("'${gap.text}' ran an action tool: ${turn.executedTools}",
                turn.executedTools.none { it in V4Assert.ACTION_TOOLS })
            assertEquals("'${gap.text}' produced a side effect", 0,
                turn.newCalendarDrafts.size + turn.newComposeDrafts.size)
            assertTrue(
                "'${gap.text}' did not say what was missing: ${turn.answer}",
                gap.mustMention.any { turn.answer.contains(it) },
            )
            h.close()
        }
    }

    @Test
    fun `a compose request with no recipient asks who and runs nothing`() = runBlocking {
        COMPOSE_GAPS.forEach { gap ->
            val h = harness(V4Cards.ANCHOR)
            val turn = h.turn(gap.text)

            assertEquals(
                "'${gap.text}' -> ${turn.outcomeType}",
                TurnOutcomeType.CLARIFICATION_REQUIRED, turn.outcomeType,
            )
            assertEquals(0, turn.newComposeDrafts.size)
            assertTrue(
                "'${gap.text}' did not say what was missing: ${turn.answer}",
                gap.mustMention.any { turn.answer.contains(it) },
            )
            h.close()
        }
    }

    /**
     * The contact exists but has no e-mail: the missing value is on the card, not in the sentence.
     * It is still a clarification, and the answer still has to say what is missing.
     */
    @Test
    fun `a verified contact with no address is a clarification not a completed request`() =
        runBlocking {
            val h = harness(V4Cards.NO_EMAIL)
            h.turn("${V4Cards.NO_EMAIL.name} 명함 찾아줘.")

            val turn = h.turn("그 사람에게 ${V4Phrasing.MAIL_REQUESTS.first()}")

            assertEquals(TurnOutcomeType.CLARIFICATION_REQUIRED, turn.outcomeType)
            assertEquals(0, turn.newComposeDrafts.size)
            assertTrue(
                "the reply did not mention the missing e-mail: ${turn.answer}",
                turn.answer.contains("이메일") || turn.answer.contains("메일 주소"),
            )
            h.close()
        }

    /** Never GENERAL_INFORMATION for any of them. */
    @Test
    fun `no missing-slot turn is recorded as general information`() = runBlocking {
        (CALENDAR_GAPS + COMPOSE_GAPS).forEach { gap ->
            val h = harness(V4Cards.ANCHOR)
            val turn = h.turn(gap.text)
            assertTrue(
                "'${gap.text}' was recorded as GENERAL_INFORMATION",
                turn.outcomeType != TurnOutcomeType.GENERAL_INFORMATION,
            )
            h.close()
        }
    }

    /** The control: a complete request is not turned into a clarification. */
    @Test
    fun `a complete request still completes`() = runBlocking {
        val h = harness(V4Cards.ANCHOR)
        h.turn("${V4Cards.ANCHOR.name} 명함 찾아줘.")

        val mail = h.turn("그 사람에게 ${V4Phrasing.MAIL_REQUESTS.first()}")
        assertEquals(V4Cards.ANCHOR.email, mail.newComposeDrafts.single().to)
        assertEquals(TurnOutcomeType.COMPOSE_OPENED, mail.outcomeType)

        val cal = h.turn("그 사람과 ${V4Phrasing.CALENDAR_REQUESTS.first()}")
        assertEquals(1, cal.newCalendarDrafts.size)
        assertEquals(TurnOutcomeType.CALENDAR_OPENED, cal.outcomeType)
        h.close()
    }
}
