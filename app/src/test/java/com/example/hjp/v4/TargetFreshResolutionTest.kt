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
 * Having heard of someone earlier is not permission to act on them now.
 *
 * `TargetFailClosedTest` covers the case where the new name resolves to nothing the store knows. The
 * gap it leaves is the opposite one: the new name *is* known to the conversation — mentioned a few
 * turns ago, or already fetched with `get_contact` — and the question is whether that history is
 * allowed to stand in for resolving the target on this turn.
 *
 * It is not. A past mention says the name was heard, not that this turn's target is that person, and
 * a past `get_contact` says the details were correct then, not that they are the target now. Every
 * turn that acts resolves its own target; if that resolution does not happen, the turn asks rather
 * than reusing whoever the session was last pointed at.
 */
class TargetFreshResolutionTest {

    private val focus = BusinessCardRecord(
        id = "F001", name = "천유겸", company = "한들소재", title = "품질관리",
        industry = "소재", email = "yugyeom@handeul.example.net", mobile = "010-7070-0001",
    )
    private val mentioned = BusinessCardRecord(
        id = "F002", name = "남지후", company = "새벽물류", title = "운영팀장",
        industry = "물류", email = "jihu@saebyeok.example.net", mobile = "010-7070-0002",
    )
    private val third = BusinessCardRecord(
        id = "F003", name = "구하람", company = "온빛교육", title = "연구원",
        industry = "교육", email = "haram@onbit.example.net", mobile = "010-7070-0003",
    )

    private val MAIL = "제목은 일정 공유, 내용은 확인 부탁드립니다 라고 메일 작성해줘."

    private fun harness(vararg cards: BusinessCardRecord) =
        MultiturnScenarioHarness(cards = cards.toList())

    private fun MultiturnScenarioHarness.assertNeverContacted(vararg values: String) {
        values.forEach { value ->
            assertTrue("$value was used as a compose recipient", messages.drafts.none { it.to == value })
            assertTrue("$value was used as an attendee", calendar.drafts.none { value in it.attendeeEmails })
        }
    }

    @Test
    fun `a name mentioned earlier in the conversation is not a resolved target now`() = runBlocking {
        val harness = harness(focus, mentioned, third)
        try {
            harness.turn("${focus.name} 명함 찾아줘.")
            // 남지후 is named here, but only as the subject of a question — nothing verifies them.
            harness.turn("${mentioned.name}이라는 분도 아세요?")
            // Now an anaphor. The session's last verified person is 천유겸; the last *named* person
            // is 남지후. Neither may be silently chosen: the turn has to ask.
            val acted = harness.turn("그 사람에게 $MAIL")

            assertEquals(
                "an anaphor split between a verified focus and a bare mention must ask, not guess",
                TurnOutcomeType.CLARIFICATION_REQUIRED,
                acted.outcomeType,
            )
            assertTrue("no message may be drafted from an unresolved anaphor", acted.newComposeDrafts.isEmpty())
            harness.assertNeverContacted(focus.email, mentioned.email, third.email)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a person fetched earlier still has to be resolved again before a later action`() = runBlocking {
        val harness = harness(focus, mentioned, third)
        try {
            harness.turn("${mentioned.name} 명함 찾아줘.")
            harness.turn("그 사람 회사가 어디인가요?")
            // Target moves to 천유겸 and is verified.
            harness.turn("${focus.name} 명함 찾아줘.")
            // "아까 그분" points backwards. A get_contact in an earlier turn does not make 남지후 the
            // target of this one; the reference is ambiguous and must be asked about.
            val acted = harness.turn("아까 그분에게 $MAIL")

            assertTrue(
                "a backwards reference must not silently reuse an earlier fetch: " +
                    "${acted.outcomeType} with ${acted.newComposeDrafts.map { it.to }}",
                acted.newComposeDrafts.none { it.to == mentioned.email },
            )
            if (acted.newComposeDrafts.isNotEmpty()) {
                assertEquals(
                    "if the turn does act, it may only act on the target it resolved this turn",
                    listOf(focus.email),
                    acted.newComposeDrafts.map { it.to },
                )
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `an explicit name overrides both the focus and any earlier mention`() = runBlocking {
        val harness = harness(focus, mentioned, third)
        try {
            harness.turn("${focus.name} 명함 찾아줘.")
            harness.turn("${mentioned.name}은 어떤 분이었죠?")
            // Explicit and unambiguous: 구하람. Neither the focus nor the mention may win.
            val acted = harness.turn("${third.name}에게 $MAIL")

            assertEquals(
                "an explicitly named, resolvable person is the target",
                listOf(third.email),
                acted.newComposeDrafts.map { it.to },
            )
            harness.assertNeverContacted(focus.email, mentioned.email)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a failed lookup of a previously mentioned person does not fall back to that person`() = runBlocking {
        // 남지후 is in the store and gets mentioned, then the user asks for someone the store does
        // not have. The turn must not decide that the closest thing it heard recently will do.
        val harness = harness(focus, mentioned)
        try {
            harness.turn("${mentioned.name} 명함 찾아줘.")
            harness.turn("고윤슬 명함 띄워줘.")
            val acted = harness.turn("그분에게 $MAIL")

            assertTrue("nothing may be drafted", acted.newComposeDrafts.isEmpty())
            harness.assertNeverContacted(focus.email, mentioned.email)
            assertNull(
                "the unresolved lookup must leave no actionable focus behind",
                acted.memory.selectedContact?.cardId,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `the guarantee holds for the calendar channel too`() = runBlocking {
        val harness = harness(focus, mentioned)
        try {
            harness.turn("${focus.name} 명함 찾아줘.")
            harness.turn("${mentioned.name}도 아는 분인가요?")
            harness.turn("그 사람이랑 2027년 3월 4일 오후 2시에 미팅 일정 잡아줘.")

            harness.assertNeverContacted(focus.email, mentioned.email)
        } finally {
            harness.close()
        }
    }
}
