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
 * A request missing something the tool needs is a question, not an answer.
 *
 * The weak spot this covers is that "the agent did not do the wrong thing" is cheap to satisfy by
 * doing nothing and saying something friendly. That reads as a pass to a metric that only counts
 * side effects, and it is indistinguishable from a general chat reply. So each case pins, per action
 * tool: no action tool ran, no draft was produced, no target was left selected, and the reply names
 * the slot that is missing so the user can supply it.
 *
 * ## What is deliberately not asserted here
 *
 * The instruction this test comes from also asks that the *typed outcome* be
 * [TurnOutcomeType.CLARIFICATION_REQUIRED] rather than `GENERAL_INFORMATION`. That change was
 * implemented, measured and reverted: v1, v2 and v3 all froze `GENERAL_INFORMATION` as the expected
 * outcome for these turns, so adopting it puts production in conflict with three frozen baselines
 * and fails their gates. Editing frozen data or lowering a floor to absorb that is exactly what is
 * ruled out, so the change needs a decision to re-baseline before it can land. The measured cost is
 * recorded in `tools/agent_eval/results/pre_device_v4/contract_change/missing_slot_outcome_typing.json`.
 *
 * The behavioural half of the requirement is enforced here and does not depend on that decision.
 */
class MissingSlotOutcomeTest {

    private val person = BusinessCardRecord(
        id = "S001", name = "표하윤", company = "너울건설", title = "안전관리",
        industry = "건설", email = "hayun@neoul.example.net", mobile = "010-6060-0001",
    )

    /** Outcomes that claim the action happened. A missing-slot turn may never carry one. */
    private val succeededActions = setOf(
        TurnOutcomeType.COMPOSE_OPENED,
        TurnOutcomeType.CALENDAR_OPENED,
        TurnOutcomeType.UPDATE_COMPLETED,
    )

    private val actionTools = setOf(
        "open_compose",
        "create_calendar_event",
        "update_business_card",
    )

    private fun harness() = MultiturnScenarioHarness(cards = listOf(person))

    /**
     * The shared contract. [asksAbout] are words any usable question about the missing slot would
     * contain — the assertion is that the reply mentions at least one, not that it uses a fixed
     * sentence.
     */
    private fun assertAsksForMissingSlot(
        setup: List<String>,
        request: String,
        vararg asksAbout: String,
    ) = runBlocking {
        val harness = harness()
        try {
            setup.forEach { harness.turn(it) }
            val record = harness.turn(request)

            assertTrue(
                "a turn that could not act must not be typed as one that did: ${record.outcomeType}",
                record.outcomeType !in succeededActions,
            )
            assertEquals(
                "no action tool may run while a required slot is unknown",
                emptyList<String>(),
                record.executedTools.filter { it in actionTools },
            )
            assertTrue(
                "no draft may be produced",
                record.newComposeDrafts.isEmpty() && record.newCalendarDrafts.isEmpty(),
            )
            assertTrue(
                "the reply has to name what is missing so the user can answer it; " +
                    "expected one of ${asksAbout.toList()} in: ${record.answer}",
                asksAbout.any { record.answer.contains(it) },
            )
            assertNull(
                "an unanswerable request must not leave a target selected for the next turn",
                record.memory.selectedContact?.cardId,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a compose with no recipient asks who`() = assertAsksForMissingSlot(
        setup = emptyList(),
        request = "제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘.",
        "수신자", "누구", "받는",
    )

    @Test
    fun `a compose to a person the store does not have asks rather than inventing an address`() =
        assertAsksForMissingSlot(
            setup = listOf("${person.name} 명함 찾아줘."),
            request = "설가온에게 제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘.",
            "찾지 못", "없", "확인",
        )

    @Test
    fun `a schedule with no time asks when`() = assertAsksForMissingSlot(
        setup = emptyList(),
        request = "점검 일정 하나 만들어줘.",
        "날짜", "시각", "언제",
    )

    @Test
    fun `an anaphor with nothing to point at asks who`() = assertAsksForMissingSlot(
        setup = emptyList(),
        request = "그 사람에게 도착했다고 문자 작성해줘.",
        "수신자", "누구", "어떤 분", "확인",
    )

    @Test
    fun `a card edit with no target asks whose card`() = assertAsksForMissingSlot(
        setup = emptyList(),
        request = "메모를 우선연락으로 수정해줘.",
        "명함", "이름", "누구",
    )

    @Test
    fun `filling the slot afterwards lets the same request through`() = runBlocking {
        // Without this the suite would be satisfied by an agent that asks for clarification and
        // then never acts. The question has to be a step towards doing the thing.
        val harness = harness()
        try {
            val asked = harness.turn("제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘.")
            assertTrue(
                "the first attempt must not draft anything",
                asked.newComposeDrafts.isEmpty(),
            )

            harness.turn("${person.name} 명함 찾아줘.")
            val acted = harness.turn("그 사람에게 제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘.")

            assertEquals(
                "once the recipient is known the request goes through, to that person",
                listOf(person.email),
                acted.newComposeDrafts.map { it.to },
            )
        } finally {
            harness.close()
        }
    }
}
