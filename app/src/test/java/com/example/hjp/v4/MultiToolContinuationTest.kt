package com.example.hjp.v4

import com.example.hjp.MultiturnScenarioHarness
import com.example.hjp.v4.ScriptedModel.Companion.calendar
import com.example.hjp.v4.ScriptedModel.Companion.compose
import com.example.hjp.v4.ScriptedModel.Companion.get
import com.example.hjp.v4.ScriptedModel.Companion.prose
import com.example.hjp.v4.ScriptedModel.Companion.search
import com.example.hjp.v4.ScriptedModel.Companion.update
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A workflow that stops halfway is not a finished turn.
 *
 * The desktop Gemma run ended several requests with a sentence about the contact it had just looked
 * up, never calling the tool the user actually asked for. The turn was recorded as complete, because
 * nothing in the loop distinguished "the model has said everything it needs to" from "the model
 * stopped narrating before doing the work".
 *
 * The rule this pins: while a turn still owes a terminal tool, prose does not end it. The kernel
 * gives the model one bounded chance to call the tool; if it narrates again the turn ends as
 * unfinished, and it never claims success. Safety comes first — the repair only fires when acting
 * would be safe, so an unresolved target or a missing slot ends in a question instead.
 *
 * Everything here runs the production `AgentKernel`, `AgentWorkflowSession`, registry and executor.
 * Only the model boundary is scripted, because a deterministic local router cannot produce the
 * failure being guarded against.
 */
class MultiToolContinuationTest {

    private val target = BusinessCardRecord(
        id = "C001", name = "표하윤", company = "너울건설", title = "안전관리",
        industry = "건설", email = "hayun@neoul.example.net", mobile = "010-6060-0001",
    )
    private val other = BusinessCardRecord(
        id = "C002", name = "남지후", company = "새벽물류", title = "운영팀장",
        industry = "물류", email = "jihu@saebyeok.example.net", mobile = "010-6060-0002",
    )

    private val MAIL = "표하윤에게 제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘."
    private val MEETING = "표하윤이랑 2027년 3월 4일 오후 2시에 점검 일정 잡아줘."
    private val EDIT = "표하윤 명함 메모를 우선연락으로 수정해줘."

    private fun harness(vararg script: ScriptedModel.Step): Pair<MultiturnScenarioHarness, ScriptedModel> {
        val model = ScriptedModel(script.toList())
        return MultiturnScenarioHarness(
            cards = listOf(target, other),
            modelOverride = model,
        ) to model
    }

    // ---- the intermediate steps stand on their own ------------------------------------------------

    @Test
    fun `search then get is a complete read`() = runBlocking {
        val (harness, _) = harness(
            search("표하윤"),
            get(target.id),
            prose("표하윤 님은 너울건설 안전관리입니다."),
        )
        try {
            val record = harness.turn("표하윤 명함 찾아줘.")
            assertEquals(listOf("search_contacts", "get_contact"), record.executedTools)
            assertTrue("a read owes no terminal tool", record.newComposeDrafts.isEmpty())
        } finally {
            harness.close()
        }
    }

    // ---- each terminal tool completes when the model calls it -------------------------------------

    @Test
    fun `search then get then compose`() = runBlocking {
        val (harness, _) = harness(
            search("표하윤"), get(target.id), compose(target.email),
            prose("작성 화면을 열었습니다."),
        )
        try {
            val record = harness.turn(MAIL)
            assertEquals(
                listOf("search_contacts", "get_contact", "open_compose"),
                record.executedTools,
            )
            assertEquals(listOf(target.email), record.newComposeDrafts.map { it.to })
            assertEquals(TurnOutcomeType.COMPOSE_OPENED, record.outcomeType)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `search then get then calendar`() = runBlocking {
        val (harness, _) = harness(
            search("표하윤"), get(target.id, "calendar"),
            calendar("2027-03-04T14:00", "2027-03-04T15:00", target.email),
            prose("일정 작성 화면을 열었습니다."),
        )
        try {
            val record = harness.turn(MEETING)
            assertTrue(
                "the calendar tool must have run: ${record.executedTools}",
                "create_calendar_event" in record.executedTools,
            )
            assertEquals(1, record.newCalendarDrafts.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `search then get then update`() = runBlocking {
        val (harness, _) = harness(
            search("표하윤"), get(target.id, "display"), update(target.id, "우선연락"),
            prose("명함을 수정했습니다."),
        )
        try {
            val record = harness.turn(EDIT)
            assertTrue(
                "the update tool must have run: ${record.executedTools}",
                "update_business_card" in record.executedTools,
            )
        } finally {
            harness.close()
        }
    }

    // ---- the repair itself -------------------------------------------------------------------------

    @Test
    fun `prose after get is repaired into the terminal tool`() = runBlocking {
        // The exact Gemma trace: lookup, lookup, then a sentence. The kernel must not accept it.
        val (harness, model) = harness(
            search("표하윤"), get(target.id),
            prose("표하윤 님은 너울건설 안전관리입니다."),
            compose(target.email),
            prose("작성 화면을 열었습니다."),
        )
        try {
            val record = harness.turn(MAIL)

            assertEquals(
                "the repair must produce the tool the request needed",
                listOf("search_contacts", "get_contact", "open_compose"),
                record.executedTools,
            )
            assertEquals(listOf(target.email), record.newComposeDrafts.map { it.to })
            assertTrue(
                "the repair goes through the tool-result channel, so the model sees it as a " +
                    "workflow state and not as something the user said: ${model.received}",
                model.received.any { it.contains("workflow_incomplete") },
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `prose again after the repair fails closed`() = runBlocking {
        val (harness, _) = harness(
            search("표하윤"), get(target.id),
            prose("표하윤 님은 너울건설 안전관리입니다."),
            prose("도움이 되었길 바랍니다."),
        )
        try {
            val record = harness.turn(MAIL)

            assertEquals(
                "no terminal tool ran, so none may be reported",
                listOf("search_contacts", "get_contact"),
                record.executedTools,
            )
            assertTrue("nothing may be drafted", record.newComposeDrafts.isEmpty())
            assertTrue(
                "the turn must say it did not finish rather than narrate: ${record.answer}",
                record.answer.contains("완료하지 못") || record.answer.contains("열지 못"),
            )
            assertTrue(
                "and it must never claim the action happened",
                listOf("보냈", "전송했", "발송했").none { record.answer.contains(it) },
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `the repair is bounded`() = runBlocking {
        // Five prose replies in a row. The turn must end, not loop.
        val (harness, model) = harness(
            search("표하윤"), get(target.id),
            prose("하나"), prose("둘"), prose("셋"), prose("넷"), prose("다섯"),
        )
        try {
            harness.turn(MAIL)
            assertTrue(
                "the script must not have been drained, which is what an unbounded retry would do: " +
                    "consumed ${model.consumed}",
                model.consumed < 7,
            )
        } finally {
            harness.close()
        }
    }

    // ---- safety outranks completion ----------------------------------------------------------------

    @Test
    fun `a missing slot is asked about instead of repaired`() = runBlocking {
        // No time in the request, so there is nothing safe to continue with: the honest end is a
        // question, not a guessed schedule.
        val (harness, _) = harness(
            search("표하윤"), get(target.id, "calendar"),
            prose("표하윤 님 정보입니다."),
        )
        try {
            val record = harness.turn("표하윤이랑 점검 일정 잡아줘.")
            assertTrue(
                "no calendar event may be created without a time: ${record.executedTools}",
                "create_calendar_event" !in record.executedTools,
            )
            assertTrue("nothing may be drafted", record.newCalendarDrafts.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a stale target blocks the action entirely`() = runBlocking {
        // The model tries to compose to the person from an earlier turn. Provenance is not satisfied
        // by having seen the address once, so the call must not execute.
        val (harness, _) = harness(
            search("표하윤"), get(target.id), compose(target.email),
            prose("작성 화면을 열었습니다."),
            // second turn: a different person is named, and the model reaches for the old address
            compose(target.email),
            prose("작성 화면을 열었습니다."),
        )
        try {
            harness.turn(MAIL)
            val second = harness.turn("남지후에게 도착했다고 문자 작성해줘.")

            assertTrue(
                "the previous person must never receive the second message",
                second.newComposeDrafts.none { it.to == target.email },
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a correction starts a fresh chain`() = runBlocking {
        val (harness, _) = harness(
            search("표하윤"), get(target.id), compose(target.email),
            prose("작성 화면을 열었습니다."),
            search("남지후"), get(other.id, "sms"), compose(other.email, "sms"),
            prose("작성 화면을 열었습니다."),
        )
        try {
            harness.turn(MAIL)
            val corrected = harness.turn("아니, 남지후에게 보내달라는 뜻이었어. 도착했다고 문자 작성해줘.")

            assertTrue(
                "after a correction the action may only reach the corrected person: " +
                    "${corrected.newComposeDrafts.map { it.to }}",
                corrected.newComposeDrafts.none { it.to == target.email },
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a side effect is never repeated by a repair`() = runBlocking {
        // The tool already ran. A model that keeps narrating afterwards must not cause a second
        // compose screen: the guard is that the workflow owes nothing once the terminal tool
        // succeeded, so no repair is issued at all.
        val (harness, _) = harness(
            search("표하윤"), get(target.id), compose(target.email),
            prose("표하윤 님께 보낼 준비가 되었습니다."),
            compose(target.email),
            prose("작성 화면을 열었습니다."),
        )
        try {
            val record = harness.turn(MAIL)
            assertEquals(
                "exactly one compose screen for one request",
                1,
                record.newComposeDrafts.size,
            )
            assertEquals(
                1,
                record.executedTools.count { it == "open_compose" },
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a cancelled request runs nothing`() = runBlocking {
        val (harness, _) = harness(prose("알겠습니다. 취소했습니다."))
        try {
            val record = harness.turn("아니야, 방금 요청은 취소해줘.")
            assertEquals(
                "a cancellation owes no terminal tool",
                emptyList<String>(),
                record.executedTools,
            )
            assertTrue(record.newComposeDrafts.isEmpty() && record.newCalendarDrafts.isEmpty())
        } finally {
            harness.close()
        }
    }
}
