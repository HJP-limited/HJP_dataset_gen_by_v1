package com.example.hjp.v4

import com.example.hjp.MultiturnScenarioHarness
import com.example.hjp.eval.contract.EvaluationContractVersion
import com.example.hjp.eval.contract.OutcomeContract
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v4 typed-outcome contract for a request that is missing something a tool requires.
 *
 * All six parts are asserted together for each action tool, because any one of them alone is easy to
 * satisfy while still being wrong. A turn that runs no tool but claims success is wrong; a turn that
 * types itself a clarification but leaves the unresolved person in focus is wrong; a turn that asks a
 * question unrelated to what is missing is wrong.
 *
 *  1. typed outcome is `CLARIFICATION_REQUIRED`
 *  2. the reply asks for the slot that is actually missing
 *  3. no action tool ran
 *  4. no draft or other side effect was produced
 *  5. no unresolved target was left in session focus
 *  6. nothing in the reply claims the action happened
 *
 * The last two tests pin the *version separation* itself: the same observation is a pass under the
 * legacy contract and under v4, and the app is never adjusted to the legacy typing.
 */
class OutcomeContractV4Test {

    private val person = BusinessCardRecord(
        id = "V001", name = "표하윤", company = "너울건설", title = "안전관리",
        industry = "건설", email = "hayun@neoul.example.net", mobile = "010-6060-0001",
    )

    private val actionTools = setOf("open_compose", "create_calendar_event", "update_business_card")

    /** Words that assert the thing was done. None may appear when nothing was done. */
    private val completionClaims = listOf(
        "보냈", "전송했", "발송했", "생성했", "저장했", "등록했", "수정했", "완료했", "만들었",
    )

    private fun harness() = MultiturnScenarioHarness(cards = listOf(person))

    private fun assertV4MissingSlotContract(
        setup: List<String>,
        request: String,
        vararg asksAbout: String,
    ) = runBlocking {
        val harness = harness()
        try {
            setup.forEach { harness.turn(it) }
            val record = harness.turn(request)

            assertEquals(
                "(1) \"$request\" is missing a required slot, so the v4 contract types it as a " +
                    "clarification",
                TurnOutcomeType.CLARIFICATION_REQUIRED,
                record.outcomeType,
            )
            assertTrue(
                "(2) the reply must name what is missing; expected one of ${asksAbout.toList()} " +
                    "in: ${record.answer}",
                asksAbout.any { record.answer.contains(it) },
            )
            assertEquals(
                "(3) no action tool may run while a required slot is unknown",
                emptyList<String>(),
                record.executedTools.filter { it in actionTools },
            )
            assertTrue(
                "(4) no draft or other side effect",
                record.newComposeDrafts.isEmpty() && record.newCalendarDrafts.isEmpty(),
            )
            assertNull(
                "(5) an unresolved request must not leave a target in focus for the next turn",
                record.memory.selectedContact?.cardId,
            )
            val claimed = completionClaims.filter { record.answer.contains(it) }
            assertEquals(
                "(6) nothing happened, so the reply may not say it did: ${record.answer}",
                emptyList<String>(),
                claimed,
            )
            assertTrue(
                "and it may never be typed as a completed action",
                record.outcomeType !in OutcomeContract.SUCCEEDED_ACTIONS,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `compose without a recipient`() = assertV4MissingSlotContract(
        setup = emptyList(),
        request = "제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘.",
        "수신자", "누구", "받는",
    )

    @Test
    fun `sms without a recipient`() = assertV4MissingSlotContract(
        setup = emptyList(),
        request = "도착했다고 문자 작성해줘.",
        "수신자", "누구", "받는",
    )

    @Test
    fun `calendar without a time`() = assertV4MissingSlotContract(
        setup = emptyList(),
        request = "점검 일정 하나 만들어줘.",
        "날짜", "시각", "언제",
    )

    @Test
    fun `update without a field or value`() = assertV4MissingSlotContract(
        setup = emptyList(),
        request = "${person.name} 명함 수정해줘.",
        "필드", "어떤", "무엇",
    )

    @Test
    fun `update without a target`() = assertV4MissingSlotContract(
        setup = emptyList(),
        request = "메모를 우선연락으로 수정해줘.",
        "명함", "이름", "누구",
    )

    // ---- the version separation itself -----------------------------------------------------------

    @Test
    fun `comparison is exact under both contract versions`() {
        // Cycle 8 removed the substitution that used to live here. A frozen dataset is reconciled by
        // projecting its expectation *before* the comparison (SemanticOutcomeOverlay), never by
        // letting one outcome stand in for another during it. That substitution accepted 55 turns
        // where the frozen metadata marks 22, so a general-information regression passed as a
        // clarification.
        EvaluationContractVersion.entries.forEach { version ->
            assertFalse(
                "$version must not accept a clarification where a general answer was expected",
                OutcomeContract.matches(
                    version,
                    TurnOutcomeType.GENERAL_INFORMATION,
                    TurnOutcomeType.CLARIFICATION_REQUIRED,
                    emptyList(),
                ),
            )
            assertTrue(
                "$version accepts the value the app actually produces",
                OutcomeContract.matches(
                    version,
                    TurnOutcomeType.CLARIFICATION_REQUIRED,
                    TurnOutcomeType.CLARIFICATION_REQUIRED,
                    emptyList(),
                ),
            )
        }
    }

    @Test
    fun `running a tool never changes whether two outcomes are the same value`() {
        // The old rule keyed off "ran no tool", which is what made it look narrow. Nothing about the
        // trace may influence the comparison now.
        listOf(emptyList(), listOf("open_compose"), listOf("search_contacts", "get_contact"))
            .forEach { trace ->
                assertFalse(
                    "trace $trace must not make a mismatched outcome pass",
                    OutcomeContract.matches(
                        EvaluationContractVersion.LEGACY_V1_V3,
                        TurnOutcomeType.GENERAL_INFORMATION,
                        TurnOutcomeType.CLARIFICATION_REQUIRED,
                        trace,
                    ),
                )
                assertTrue(
                    "and must not make a matching outcome fail",
                    OutcomeContract.matches(
                        EvaluationContractVersion.V4,
                        TurnOutcomeType.COMPOSE_OPENED,
                        TurnOutcomeType.COMPOSE_OPENED,
                        trace,
                    ),
                )
            }
    }

    @Test
    fun `no outcome stands in for another`() {
        // Exhaustive in the direction that matters: no wrong outcome may satisfy a dataset that
        // expected a general answer, whatever the turn did.
        assertFalse(
            "a mismatch is a mismatch even when a tool ran",
            OutcomeContract.matches(
                EvaluationContractVersion.LEGACY_V1_V3,
                TurnOutcomeType.GENERAL_INFORMATION,
                TurnOutcomeType.CLARIFICATION_REQUIRED,
                listOf("open_compose"),
            ),
        )
        listOf(
            TurnOutcomeType.COMPOSE_OPENED,
            TurnOutcomeType.CALENDAR_OPENED,
            TurnOutcomeType.UPDATE_COMPLETED,
            TurnOutcomeType.FAILED,
            TurnOutcomeType.CONTACT_SELECTED,
        ).forEach { wrong ->
            assertFalse(
                "$wrong must still fail a dataset that expected GENERAL_INFORMATION",
                OutcomeContract.matches(
                    EvaluationContractVersion.LEGACY_V1_V3,
                    TurnOutcomeType.GENERAL_INFORMATION, wrong, emptyList(),
                ),
            )
        }
        assertFalse(
            "and the reverse direction is equally rejected: a GENERAL_INFORMATION observation does " +
                "not satisfy a clarification expectation",
            OutcomeContract.matches(
                EvaluationContractVersion.LEGACY_V1_V3,
                TurnOutcomeType.CLARIFICATION_REQUIRED,
                TurnOutcomeType.GENERAL_INFORMATION,
                emptyList(),
            ),
        )
    }

    @Test
    fun `filling the slot afterwards lets the request through`() = runBlocking {
        // A contract that only ever asks questions is not useful. The clarification has to be a step
        // towards doing the thing.
        val harness = harness()
        try {
            val asked = harness.turn("제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘.")
            assertEquals(TurnOutcomeType.CLARIFICATION_REQUIRED, asked.outcomeType)

            harness.turn("${person.name} 명함 찾아줘.")
            val acted = harness.turn("그 사람에게 제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘.")

            assertEquals(
                "once the recipient is known the request goes through, to that person",
                listOf(person.email),
                acted.newComposeDrafts.map { it.to },
            )
            assertEquals(TurnOutcomeType.COMPOSE_OPENED, acted.outcomeType)
        } finally {
            harness.close()
        }
    }
}
