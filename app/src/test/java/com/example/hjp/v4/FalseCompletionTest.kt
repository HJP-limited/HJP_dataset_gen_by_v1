package com.example.hjp.v4

import com.example.hjp.MultiturnScenarioHarness
import com.example.hjp.v4.ScriptedModel.Companion.calendar
import com.example.hjp.v4.ScriptedModel.Companion.compose
import com.example.hjp.v4.ScriptedModel.Companion.get
import com.example.hjp.v4.ScriptedModel.Companion.prose
import com.example.hjp.v4.ScriptedModel.Companion.search
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the agent is allowed to say it did.
 *
 * `open_compose` and `create_calendar_event` open a screen. The user still has to press send, or
 * save. So "보냈습니다" and "일정을 저장했습니다" are false after those tools even when everything
 * worked — and worse, they are indistinguishable to a user from the real thing.
 *
 * The check is per tool rather than per phrase: the same sentence is honest after one tool and false
 * after another, so a banned-word list would either forbid true statements or permit false ones.
 * `update_business_card` really does write, so its completion claim is only false when the write did
 * not happen.
 */
class FalseCompletionTest {

    private val target = BusinessCardRecord(
        id = "C001", name = "표하윤", company = "너울건설", title = "안전관리",
        industry = "건설", email = "hayun@neoul.example.net", mobile = "010-6060-0001",
    )

    private val MAIL = "표하윤에게 제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘."
    private val MEETING = "표하윤과 2027년 3월 4일 오후 2시에 점검 일정 잡아줘."

    private fun run(request: String, vararg script: ScriptedModel.Step): String = runBlocking {
        val harness = MultiturnScenarioHarness(
            cards = listOf(target),
            modelOverride = ScriptedModel(script.toList()),
        )
        try {
            harness.turn(request).answer
        } finally {
            harness.close()
        }
    }

    @Test
    fun `opening a composer is not sending`() {
        val answer = run(
            MAIL,
            search("표하윤"), get(target.id), compose(target.email),
            prose("표하윤 님께 메일을 전송했습니다."),
        )
        assertFalse(
            "the composer was opened, not sent, and the reply must not say otherwise: $answer",
            answer.contains("전송했습니다") || answer.contains("보냈습니다"),
        )
        assertTrue(
            "and it must say what did happen: $answer",
            answer.contains("작성 화면"),
        )
    }

    @Test
    fun `opening a calendar screen is not saving an event`() {
        val answer = run(
            MEETING,
            search("표하윤"), get(target.id, "calendar"),
            calendar("2027-03-04T14:00", "2027-03-04T15:00", target.email),
            prose("일정을 저장했습니다."),
        )
        assertFalse(
            "the insert screen was opened, not saved: $answer",
            answer.contains("저장했습니다") || answer.contains("생성했습니다"),
        )
        assertTrue(
            "and the reply must say the screen was opened: $answer",
            answer.contains("작성 화면") || answer.contains("열었습니다"),
        )
    }

    @Test
    fun `claiming completion without running any tool is refused`() {
        val answer = run(MAIL, prose("메일을 보냈습니다."))
        assertFalse(
            "nothing ran, so nothing was sent: $answer",
            answer.contains("보냈습니다"),
        )
    }

    @Test
    fun `claiming completion after only a lookup is refused`() {
        val answer = run(
            MAIL,
            search("표하윤"), get(target.id),
            prose("메일을 발송했습니다."),
            prose("메일을 발송했습니다."),
        )
        assertFalse(
            "a lookup is not a send: $answer",
            answer.contains("발송했습니다") || answer.contains("보냈습니다"),
        )
    }

    @Test
    fun `an honest screen-opened report is left alone`() {
        // The guard must not rewrite a correct answer, or it would be indistinguishable from one
        // that suppresses everything.
        val answer = run(
            MAIL,
            search("표하윤"), get(target.id), compose(target.email),
            prose("작성 화면을 열었습니다."),
        )
        assertTrue("a true statement survives: $answer", answer.contains("작성 화면을 열었습니다"))
    }

    @Test
    fun `a read-only turn may still describe what it read`() {
        val answer = run(
            "표하윤 명함 찾아줘.",
            search("표하윤"), get(target.id, "display"),
            prose("표하윤 님은 너울건설 안전관리입니다."),
        )
        assertTrue(
            "a lookup answer is not a completion claim and must not be rewritten: $answer",
            answer.contains("너울건설"),
        )
    }
}
