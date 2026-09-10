package com.example.hjp.v4

import com.example.hjp.MultiturnScenarioHarness
import com.example.hjp.v4.ScriptedModel.Companion.calendar
import com.example.hjp.v4.ScriptedModel.Companion.get
import com.example.hjp.v4.ScriptedModel.Companion.prose
import com.example.hjp.v4.ScriptedModel.Companion.search
import com.example.hjp.v4.ScriptedModel.Companion.update
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two missing-slot boundaries cycle 8 left open.
 *
 * `MissingSlot.UPDATE_TARGET` was declared and never returned: the branch that would have produced it
 * was removed while narrowing an over-broad rule, so "메모를 우선연락으로 수정해줘" with nobody in focus
 * fell through to whatever the model happened to say.
 *
 * The calendar rule required *no* date expression at all. A request with a date but no time —
 * "내일 점검 일정 만들어줘" — satisfied `relativeDateIntent` and so was treated as complete, even though
 * `create_calendar_event` needs a start *time*.
 *
 * Both are checked through a prose-only boundary, the shape the LiteRT adapter produces: the model
 * returns text with `clarification == null`, so anything that works here works because of workflow
 * state rather than a flag. Positive controls sit beside each case, because a rule that asks for a
 * slot too eagerly is its own defect.
 */
class MissingSlotBoundaryTest {

    private val person = BusinessCardRecord(
        id = "B001", name = "표하윤", company = "너울건설", title = "안전관리",
        industry = "건설", email = "hayun@neoul.example.net", mobile = "010-6060-0001",
    )
    private val other = BusinessCardRecord(
        id = "B002", name = "남지후", company = "새벽물류", title = "운영팀장",
        industry = "물류", email = "jihu@saebyeok.example.net", mobile = "010-6060-0002",
    )

    private val actionTools = setOf("open_compose", "create_calendar_event", "update_business_card")

    private fun harness(vararg script: ScriptedModel.Step) = MultiturnScenarioHarness(
        cards = listOf(person, other),
        modelOverride = ScriptedModel(script.toList()),
    )

    private fun assertAsks(
        request: String,
        vararg asksAbout: String,
        setup: List<String> = emptyList(),
        script: List<ScriptedModel.Step> = listOf(prose("무엇을 도와드릴까요?")),
    ) = runBlocking {
        val harness = harness(*script.toTypedArray())
        try {
            setup.forEach { harness.turn(it) }
            val record = harness.turn(request)

            assertEquals(
                "\"$request\" lacks a slot the tool requires",
                TurnOutcomeType.CLARIFICATION_REQUIRED,
                record.outcomeType,
            )
            assertNotEquals(TurnOutcomeType.GENERAL_INFORMATION, record.outcomeType)
            assertEquals(
                "no action tool may run",
                emptyList<String>(),
                record.executedTools.filter { it in actionTools },
            )
            assertTrue(
                "no side effect",
                record.newComposeDrafts.isEmpty() && record.newCalendarDrafts.isEmpty(),
            )
            assertNull(
                "no unresolved target left in focus",
                record.memory.selectedContact?.cardId,
            )
            assertTrue(
                "the reply must name what is missing; expected one of ${asksAbout.toList()} " +
                    "in: ${record.answer}",
                asksAbout.any { record.answer.contains(it) },
            )
        } finally {
            harness.close()
        }
    }

    // ---- B1: update with no target ------------------------------------------------------------------

    @Test
    fun `an edit with no target asks whose card`() = assertAsks(
        request = "메모를 우선연락으로 수정해줘.",
        "명함", "누구", "어떤 분",
    )

    @Test
    fun `an edit with a field and value but still no person asks whose card`() = assertAsks(
        request = "직함을 팀장으로 바꿔줘.",
        "명함", "누구", "어떤 분",
    )

    @Test
    fun `an edit naming a person the store does not have does not fall back`() = runBlocking {
        val harness = harness(search("고윤슬"), prose("고윤슬 님을 찾지 못했습니다."))
        try {
            harness.turn("${person.name} 명함 찾아줘.")
            val record = harness.turn("고윤슬 명함 메모를 우선연락으로 수정해줘.")

            assertEquals(
                "the edit must not run against whoever was in focus",
                emptyList<String>(),
                record.executedTools.filter { it == "update_business_card" },
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `an explicit target with a field and value goes through`() = runBlocking {
        // Positive control: the rule must not turn a complete edit into a question.
        val harness = harness(
            search(person.name), get(person.id, "display"), update(person.id, "우선연락"),
            prose("명함을 수정했습니다."),
        )
        try {
            val record = harness.turn("${person.name} 명함 메모를 우선연락으로 수정해줘.")
            assertTrue(
                "the edit must run: ${record.executedTools}",
                "update_business_card" in record.executedTools,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `an anaphor after a verified lookup reaches that person`() = runBlocking {
        // Positive control for the explicit-anaphora path, including the fresh get_contact before the
        // write.
        val harness = harness(
            search(person.name), get(person.id, "display"),
            prose("${person.name} 님 정보입니다."),
            get(person.id, "display"), update(person.id, "우선연락"),
            prose("명함을 수정했습니다."),
        )
        try {
            harness.turn("${person.name} 명함 찾아줘.")
            val record = harness.turn("그 사람 메모를 우선연락으로 수정해줘.")
            assertTrue(
                "an explicit anaphor over a verified contact may act: ${record.executedTools}",
                "update_business_card" in record.executedTools,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a correction discards the earlier verification`() = runBlocking {
        val harness = harness(
            search(person.name), get(person.id, "display"),
            prose("${person.name} 님 정보입니다."),
            search(other.name), get(other.id, "display"), update(other.id, "우선연락"),
            prose("명함을 수정했습니다."),
        )
        try {
            harness.turn("${person.name} 명함 찾아줘.")
            val corrected = harness.turn("아니, ${other.name} 명함 메모를 우선연락으로 수정해줘.")

            val updated = corrected.toolArguments
                .filter { it.first == "update_business_card" }
                .mapNotNull { it.second["card_id"]?.toString()?.trim('"') }
            assertTrue(
                "the edit must never land on the person the correction replaced: $updated",
                person.id !in updated,
            )
        } finally {
            harness.close()
        }
    }

    // ---- B2: calendar with a date but no time -------------------------------------------------------

    @Test
    fun `tomorrow with no time asks for the time`() = assertAsks(
        request = "내일 점검 일정 만들어줘.",
        "시각", "시간", "언제",
    )

    @Test
    fun `next week with no time asks for the time`() = assertAsks(
        request = "다음 주에 회의 잡아줘.",
        "시각", "시간", "언제",
    )

    @Test
    fun `an absolute date with no time asks for the time`() = assertAsks(
        request = "2027년 3월 4일에 점검 일정 만들어줘.",
        "시각", "시간", "언제",
    )

    @Test
    fun `a date and a time together go through`() = runBlocking {
        // Positive control.
        val harness = harness(
            calendar("2027-03-04T14:00", "2027-03-04T15:00"),
            prose("일정 작성 화면을 열었습니다."),
        )
        try {
            val record = harness.turn("2027년 3월 4일 오후 2시에 점검 일정 만들어줘.")
            assertTrue(
                "a complete request must run: ${record.executedTools}",
                "create_calendar_event" in record.executedTools,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a relative date with a time still resolves through the datetime tool`() = runBlocking {
        val harness = harness(
            ScriptedModel.Step.Call("get_current_datetime", kotlinx.serialization.json.buildJsonObject {}),
            calendar("2026-08-11T15:00", "2026-08-11T16:00"),
            prose("일정 작성 화면을 열었습니다."),
        )
        try {
            val record = harness.turn("내일 오후 3시에 점검 일정 만들어줘.")
            assertNotEquals(
                "a relative date with a stated time is complete, not a missing slot: ${record.answer}",
                TurnOutcomeType.CLARIFICATION_REQUIRED,
                record.outcomeType,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `every valid time form reaches the calendar tool instead of being asked about`() = runBlocking {
        // The user-visible half of the temporal-slot defect: these are complete requests, and the
        // agent used to ask for a time it had already been given.
        val requests = listOf(
            "14시에 점검 일정 만들어줘.",
            "2시에 점검 일정 만들어줘.",
            "정오에 점검 일정 만들어줘.",
            "자정에 점검 일정 만들어줘.",
            "새벽 3시에 점검 일정 만들어줘.",
            "저녁 7시에 점검 일정 만들어줘.",
            "14:30에 점검 일정 만들어줘.",
            "오전 9시 30분에 점검 일정 만들어줘.",
        )
        val wronglyAsked = mutableListOf<String>()
        requests.forEach { request ->
            val harness = harness(
                ScriptedModel.Step.Call("get_current_datetime", kotlinx.serialization.json.buildJsonObject {}),
                calendar("2026-08-18T14:00", "2026-08-18T15:00"),
                prose("일정 작성 화면을 열었습니다."),
            )
            try {
                val record = harness.turn(request)
                if (record.outcomeType == TurnOutcomeType.CLARIFICATION_REQUIRED) {
                    wronglyAsked += "$request -> ${record.answer.take(40)}"
                }
            } finally {
                harness.close()
            }
        }
        assertEquals(
            "a stated time must not be treated as a missing slot",
            emptyList<String>(),
            wronglyAsked,
        )
    }

    // ---- the negative controls the narrowing depends on ---------------------------------------------

    @Test
    fun `a person whose name contains a calendar word is not a schedule request`() = runBlocking {
        val named = BusinessCardRecord(
            id = "B003", name = "김일정", company = "너른들건설", title = "현장소장",
            industry = "건설", email = "iljeong@nrd.example.net", mobile = "010-6060-0003",
        )
        val harness = MultiturnScenarioHarness(
            cards = listOf(named),
            modelOverride = ScriptedModel(
                listOf(search("김일정"), get(named.id, "display"), prose("김일정 님은 너른들건설 현장소장입니다.")),
            ),
        )
        try {
            val record = harness.turn("김일정 명함 찾아줘.")
            assertNotEquals(
                "a lookup for a person named 김일정 must not become a schedule missing its time",
                TurnOutcomeType.CLARIFICATION_REQUIRED,
                record.outcomeType,
            )
            assertTrue("the lookup must run", record.executedTools.isNotEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `calendar words in a mail subject are not a schedule request`() = runBlocking {
        val harness = harness(
            search(person.name), get(person.id), ScriptedModel.compose(person.email),
            prose("작성 화면을 열었습니다."),
        )
        try {
            val record = harness.turn(
                "${person.name}에게 제목은 회의 안내, 내용은 일정 공유드립니다 라고 메일 작성해줘.",
            )
            assertNotEquals(
                "the subject mentions 회의 and the body 일정, but this is a compose request: ${record.answer}",
                TurnOutcomeType.CLARIFICATION_REQUIRED,
                record.outcomeType,
            )
            assertEquals(listOf(person.email), record.newComposeDrafts.map { it.to })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a general question about email format is not a target clarification`() = runBlocking {
        val harness = harness(prose("제목은 용건을 짧게 적습니다."))
        try {
            val record = harness.turn("이메일 제목은 어떤 형식으로 쓰면 되나요?")
            assertNotEquals(
                "a question about a concept names no person and no slot: ${record.answer}",
                TurnOutcomeType.CLARIFICATION_REQUIRED,
                record.outcomeType,
            )
        } finally {
            harness.close()
        }
    }
}
