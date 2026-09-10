package com.example.hjp.v4

import com.example.hjp.MultiturnScenarioHarness
import com.example.hjp.v4.ScriptedModel.Companion.compose
import com.example.hjp.v4.ScriptedModel.Companion.get
import com.example.hjp.v4.ScriptedModel.Companion.prose
import com.example.hjp.v4.ScriptedModel.Companion.search
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same typed contract, from a boundary that cannot say what it is doing.
 *
 * `LocalToolRoutingModelGateway` builds its own missing-slot answers, so it can mark them
 * `ClarifyReason.MISSING_REQUIRED_SLOT` on the way out. `LiteRtAgentModelGateway` cannot: an actual
 * model returns text, the adapter wraps it as `FinalCandidate(text)`, and `clarification` is null. If
 * the typed outcome came from that flag, then the same request — "메일 작성해줘" with no recipient —
 * would be a clarification on the deterministic path and a completed or general-information turn on
 * the real one. The evaluation would look clean and the shipped app would be the broken half.
 *
 * `ScriptedModel` returns prose exactly the way the LiteRT adapter does, with no clarification
 * marker, so these tests exercise the production kernel and workflow against that boundary. What they
 * hold is that the outcome comes from workflow state — which slots the request supplied and what the
 * tools did — and not from the model's wording or from a flag the boundary happened to set.
 */
class ProseOnlyBoundaryMissingSlotTest {

    private val person = BusinessCardRecord(
        id = "P001", name = "표하윤", company = "너울건설", title = "안전관리",
        industry = "건설", email = "hayun@neoul.example.net", mobile = "010-6060-0001",
    )

    private val actionTools = setOf("open_compose", "create_calendar_event", "update_business_card")

    private fun harness(vararg script: ScriptedModel.Step) = MultiturnScenarioHarness(
        cards = listOf(person),
        modelOverride = ScriptedModel(script.toList()),
    )

    /**
     * The whole contract, asserted against a boundary that returned nothing but a sentence.
     *
     * [modelProse] is what the model chose to say. It varies on purpose — a good question, a bad one,
     * an unrelated remark — because none of it may change the typed outcome.
     */
    private fun assertClarifiesFromWorkflowState(
        request: String,
        modelProse: String,
        vararg asksAbout: String,
    ) = runBlocking {
        val harness = harness(prose(modelProse))
        try {
            val record = harness.turn(request)

            assertEquals(
                "\"$request\" is missing a required slot, so the turn is a clarification even though " +
                    "the boundary set no clarification flag",
                TurnOutcomeType.CLARIFICATION_REQUIRED,
                record.outcomeType,
            )
            assertNotEquals(
                "and it must never be recorded as a general answer",
                TurnOutcomeType.GENERAL_INFORMATION,
                record.outcomeType,
            )
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
                "the user must be told what is missing; expected one of ${asksAbout.toList()} " +
                    "in: ${record.answer}",
                asksAbout.any { record.answer.contains(it) },
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `prose asking for a recipient`() = assertClarifiesFromWorkflowState(
        request = "제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘.",
        modelProse = "수신자를 알려 주세요.",
        "수신자", "누구", "받는",
    )

    @Test
    fun `prose asking for a start time`() = assertClarifiesFromWorkflowState(
        request = "점검 일정 하나 만들어줘.",
        modelProse = "시작 날짜와 시각을 알려 주세요.",
        "날짜", "시각", "언제",
    )

    @Test
    fun `prose asking which field to change`() = assertClarifiesFromWorkflowState(
        request = "${person.name} 명함 수정해줘.",
        modelProse = "수정할 필드를 알려 주세요.",
        "필드", "값", "어떤",
    )

    @Test
    fun `the outcome does not depend on how well the model worded it`() {
        // Three boundaries, three sentences, one of them not a question at all. The typed outcome is
        // the same because it is not read from the text.
        listOf(
            "수신자를 알려 주세요.",
            "메일을 준비했습니다.",
            "도움이 되었길 바랍니다.",
        ).forEach { proseText ->
            runBlocking {
                val harness = harness(prose(proseText))
                try {
                    val record = harness.turn("제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.")
                    assertEquals(
                        "prose \"$proseText\" must not change the typed outcome",
                        TurnOutcomeType.CLARIFICATION_REQUIRED,
                        record.outcomeType,
                    )
                    assertTrue(
                        "and a model that claimed to have prepared the mail must not have the claim " +
                            "shown as if it were true: ${record.answer}",
                        !record.answer.contains("준비했습니다"),
                    )
                } finally {
                    harness.close()
                }
            }
        }
    }

    // ---- and the cases that must not be mistaken for a missing slot --------------------------------

    @Test
    fun `a direct address needs no contact lookup and is not a missing slot`() = runBlocking {
        val harness = harness(
            compose("gaon@example.net"),
            prose("작성 화면을 열었습니다."),
        )
        try {
            val record = harness.turn("gaon@example.net으로 제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.")
            assertEquals(
                "the user supplied the recipient, so nothing is missing",
                TurnOutcomeType.COMPOSE_OPENED,
                record.outcomeType,
            )
            assertEquals(listOf("gaon@example.net"), record.newComposeDrafts.map { it.to })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a complete request is not turned into a question`() = runBlocking {
        val harness = harness(
            search(person.name), get(person.id), compose(person.email),
            prose("작성 화면을 열었습니다."),
        )
        try {
            val record = harness.turn(
                "${person.name}에게 제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘.",
            )
            assertEquals(TurnOutcomeType.COMPOSE_OPENED, record.outcomeType)
            assertEquals(listOf(person.email), record.newComposeDrafts.map { it.to })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `general prose about a general question is not a missing-slot clarification`() = runBlocking {
        // The mirror of the defect: over-firing would turn every unanswered question into a
        // clarification, which is the false pass this cycle removed from the evaluator.
        val harness = harness(prose("이메일 제목은 보통 용건을 짧게 적습니다."))
        try {
            val record = harness.turn("이메일 제목은 어떤 형식으로 쓰면 되나요?")
            assertNotEquals(
                "a question about a concept names no slot and must not be typed as a clarification: " +
                    "${record.answer}",
                TurnOutcomeType.CLARIFICATION_REQUIRED,
                record.outcomeType,
            )
            assertTrue(record.executedTools.filter { it in actionTools }.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a read-only lookup is not a missing-slot clarification`() = runBlocking {
        val harness = harness(
            search(person.name), get(person.id, "display"),
            prose("${person.name} 님은 ${person.company} ${person.title}입니다."),
        )
        try {
            val record = harness.turn("${person.name} 명함 찾아줘.")
            assertNotEquals(
                TurnOutcomeType.CLARIFICATION_REQUIRED,
                record.outcomeType,
            )
            assertTrue("the lookup itself must have run", record.executedTools.isNotEmpty())
        } finally {
            harness.close()
        }
    }
}
