package com.example.hjp.v4

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming somebody new closes the door on the old target until the new one is verified.
 *
 * This is the safety failure held-out v3 found, reproduced first as a test. In
 * `v3_long_range_provenance_eight_turns` the user asked for 봉예람 in turn 2, that turn failed to run
 * a lookup at all, the previous person stayed in focus, and turn 3's "그분에게" opened a mail
 * composer addressed to **the previous person**. A real message to the wrong recipient.
 *
 * Two rules have to hold, and they are independent:
 *
 *  - a turn that names a new target must retire the old one *even when the lookup fails*, so a later
 *    anaphor resolves to nobody rather than to the previous person;
 *  - once a new target is verified, actions go to that person and only that person.
 *
 * Everything here is expressed over the ordinary kernel with the ordinary tools. No test names an
 * evaluation case, and the cards are declared locally so the contract is about the rule, not a
 * fixture.
 */
class TargetFailClosedTest {

    private val known = BusinessCardRecord(
        id = "T001", name = "차솔빈", company = "이레바이오", title = "공정개발",
        industry = "바이오", email = "solbin@irebio.example.net", mobile = "010-9090-0001",
    )
    private val other = BusinessCardRecord(
        id = "T002", name = "봉예람", company = "달빛출판", title = "편집장",
        industry = "출판", email = "yeram@dalbit.example.net", mobile = "010-9090-0002",
    )
    private val twinA = BusinessCardRecord(
        id = "T003", name = "탁보미", company = "정명법무", title = "법무사",
        industry = "법률", email = "bomi.law@jm.example.net", mobile = "010-9090-0003",
    )
    private val twinB = BusinessCardRecord(
        id = "T004", name = "탁보미", company = "결디자인", title = "UX디자이너",
        industry = "디자인", email = "bomi.ux@gy.example.net", mobile = "010-9090-0004",
    )

    private val MAIL = "제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘."
    private val SMS = "도착했다고 문자 작성해줘."

    private fun harness(vararg cards: BusinessCardRecord) =
        MultiturnScenarioHarness(cards = cards.toList())

    /** Every address the scenario must never have used, checked against both external surfaces. */
    private fun MultiturnScenarioHarness.assertNeverContacted(vararg values: String) {
        values.forEach { value ->
            assertTrue("$value was used as a compose recipient", messages.drafts.none { it.to == value })
            assertTrue("$value was used as an attendee", calendar.drafts.none { value in it.attendeeEmails })
        }
    }

    // ---- the reproduction ------------------------------------------------------------------------

    /**
     * The exact v3 trace. The contract is not "no second message" — the user asked for somebody, and
     * if that person resolves they should get it. The contract is that the message can never go to
     * the *previous* person, which is what happened when the 띄워줘 turn silently did nothing.
     */
    @Test
    fun `naming somebody new never lets the following anaphor reach the previous person`() = runBlocking {
        val h = harness(known, other)
        h.turn("차솔빈 명함 찾아줘.")
        h.turn("그 사람에게 $MAIL")
        assertEquals(known.email, h.messages.drafts.single().to)

        h.turn("봉예람 연락처 좀 띄워줘.")
        val after = h.turn("그분에게 $MAIL")

        val newDraft = after.newComposeDrafts.singleOrNull()
        assertTrue(
            "the follow-up addressed the previous person: ${newDraft?.to}",
            newDraft == null || newDraft.to == other.email,
        )
        h.assertNeverContacted(known.mobile)
        assertEquals(1, h.messages.drafts.count { it.to == known.email })
        h.close()
    }

    @Test
    fun `a new target that the store does not have blocks every side-effecting tool`() = runBlocking {
        listOf(
            "그분에게 $MAIL",
            "그분에게 $SMS",
            "그 사람과 2027년 5월 6일 오후 4시 협의 일정 만들어줘.",
            "그 사람 메모를 재검토로 수정해줘.",
        ).forEach { follow ->
            val h = harness(known)
            h.turn("차솔빈 명함 찾아줘.")
            h.turn("봉예람 명함 찾아줘.") // not in this store

            val turn = h.turn(follow)

            assertEquals("$follow opened a screen", 0, turn.newComposeDrafts.size + turn.newCalendarDrafts.size)
            assertTrue("$follow wrote a card", turn.executedTools.none { it == "update_business_card" })
            h.assertNeverContacted(known.email, known.mobile)
            h.close()
        }
    }

    @Test
    fun `an ambiguous new target blocks the action and asks which person`() = runBlocking {
        val h = harness(known, twinA, twinB)
        h.turn("차솔빈 명함 찾아줘.")
        h.turn("탁보미 명함 찾아줘.")

        val turn = h.turn("그 사람에게 $MAIL")

        assertEquals(0, turn.newComposeDrafts.size)
        assertEquals(TurnOutcomeType.CLARIFICATION_REQUIRED, turn.outcomeType)
        h.assertNeverContacted(known.email, twinA.email, twinB.email)
        h.close()
    }

    // ---- the other direction: a verified new target must be reachable -----------------------------

    @Test
    fun `a new target that resolves takes over completely`() = runBlocking {
        val h = harness(known, other)
        h.turn("차솔빈 명함 찾아줘.")
        h.turn("그 사람에게 $MAIL")
        h.turn("봉예람 명함 찾아줘.")

        val turn = h.turn("그분에게 $MAIL")

        assertEquals(listOf("get_contact", "open_compose"), turn.executedTools)
        assertEquals(other.email, turn.newComposeDrafts.single().to)
        assertEquals(other.id, turn.memory.selectedContact?.cardId)
        h.close()
    }

    /** The reference path itself must keep working, or the fix would have broken ordinary use. */
    @Test
    fun `an anaphor with no new candidate still reaches the verified focus`() = runBlocking {
        val h = harness(known)
        h.turn("차솔빈 명함 찾아줘.")

        val mail = h.turn("그 사람에게 $MAIL")
        val sms = h.turn("그분에게 $SMS")
        val cal = h.turn("그 사람과 2027년 5월 6일 오후 4시 협의 일정 만들어줘.")

        assertEquals(known.email, mail.newComposeDrafts.single().to)
        assertEquals(known.mobile, sms.newComposeDrafts.single().to)
        assertEquals(listOf(known.email), cal.newCalendarDrafts.single().attendeeEmails)
        // Every one of them re-read the card in its own turn.
        listOf(mail, sms, cal).forEach {
            assertTrue(it.text, it.executedTools.first() == "get_contact")
        }
        h.close()
    }

    // ---- correction, reset and staleness ----------------------------------------------------------

    @Test
    fun `a correction retires the rejected person for side effects`() = runBlocking {
        val h = harness(known, other)
        h.turn("차솔빈 명함 찾아줘.")
        h.turn("차솔빈 말고 봉예람 명함 찾아줘.")

        val turn = h.turn("그 사람에게 $MAIL")

        assertEquals(other.email, turn.newComposeDrafts.singleOrNull()?.to)
        h.assertNeverContacted(known.email)
        h.close()
    }

    @Test
    fun `a new conversation makes the previous target unreachable`() = runBlocking {
        val h = harness(known)
        h.turn("차솔빈 명함 찾아줘.")
        h.reset()

        val turn = h.turn("그 사람에게 $MAIL")

        assertEquals(0, turn.newComposeDrafts.size)
        assertEquals(TurnOutcomeType.CLARIFICATION_REQUIRED, turn.outcomeType)
        assertNull(turn.memory.selectedContact)
        h.assertNeverContacted(known.email)
        h.close()
    }

    @Test
    fun `a zero-result search clears the focus for every channel`() = runBlocking {
        val h = harness(known)
        h.turn("차솔빈 명함 찾아줘.")
        h.turn("갈원비 명함 찾아줘.")

        listOf("그 사람에게 $MAIL", "그분에게 $SMS").forEach { follow ->
            val turn = h.turn(follow)
            assertEquals("$follow opened a screen", 0, turn.newComposeDrafts.size)
        }
        h.assertNeverContacted(known.email, known.mobile)
        h.close()
    }
}
