package com.example.hjp.eval

import com.example.hjp.MultiturnScenarioHarness.Companion.JIWON
import com.example.hjp.MultiturnScenarioHarness.Companion.MINSU
import com.example.hjp.MultiturnScenarioHarness.Companion.NO_EMAIL
import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.contract.TurnOutcomeType

/**
 * The ten defects the legacy evaluator scored as passes.
 *
 * Each one is written against the *structural* invariant in its row of the hardening brief, not
 * against a sentence: what the turn is classified as, which tools it may run, which card it acts on
 * and how many irreversible actions it is allowed. Any of them would still fail if the wording
 * changed but the behaviour regressed.
 */
object KnownRegressionCases {
    private const val SEARCH = "search_contacts"
    private const val GET = "get_contact"
    private const val UPDATE = "update_business_card"

    private val findJiwon = TurnSpec(
        user = "김지원 명함 찾아줘.",
        expectedAct = DialogueAct.CONTACT_SEARCH,
        expectedOutcome = TurnOutcomeType.CONTACT_SELECTED,
        expectedTools = listOf(SEARCH),
        expectedSelectedCardId = "C001",
    )

    val ALL: List<MultiturnSpec> = listOf(
        // 1. An attribute question must return the grounded value, not a capability blurb.
        MultiturnSpec(
            id = "ref_attribute_question",
            primaryCategory = EvalCategories.REFERENCE,
            secondaryTags = listOf("attribute", "fresh_read"),
            cards = listOf(JIWON),
            turns = listOf(
                findJiwon,
                TurnSpec(
                    user = "회사가 어디야?",
                    expectedAct = DialogueAct.CONTACT_DETAIL,
                    expectedOutcome = TurnOutcomeType.CONTACT_DETAIL_SHOWN,
                    expectedTools = listOf(GET),
                    expectedArgs = mapOf(GET to mapOf("card_id" to "C001")),
                    expectedSelectedCardId = "C001",
                    answerMustContain = listOf("비전글로벌"),
                    answerMustNotContain = listOf("처리할 수 있습니다"),
                ),
            ),
        ),
        // 2. The first person named must resolve to that person, end to end.
        MultiturnSpec(
            id = "ref_first_mentioned_person",
            primaryCategory = EvalCategories.REFERENCE,
            secondaryTags = listOf("long_range", "first_mention"),
            turns = listOf(
                findJiwon,
                TurnSpec(
                    user = "최영희 명함 찾아줘.",
                    expectedAct = DialogueAct.CONTACT_SEARCH,
                    expectedOutcome = TurnOutcomeType.CONTACT_SELECTED,
                    expectedTools = listOf(SEARCH),
                    expectedSelectedCardId = "C004",
                ),
                TurnSpec(
                    user = "처음 말한 사람 명함 보여줘.",
                    expectedAct = DialogueAct.CONTACT_SELECTION,
                    expectedOutcome = TurnOutcomeType.CONTACT_DETAIL_SHOWN,
                    expectedTools = listOf(GET),
                    expectedArgs = mapOf(GET to mapOf("card_id" to "C001")),
                    expectedSelectedCardId = "C001",
                    answerMustContain = listOf("김지원"),
                    answerMustNotContain = listOf("완료하지 못했습니다"),
                ),
            ),
        ),
        // 3. An ordinal indexes the candidate list, and the answer is about that candidate.
        MultiturnSpec(
            id = "ordinal_second_person",
            primaryCategory = EvalCategories.REFERENCE,
            secondaryTags = listOf("ordinal", "duplicate_name"),
            turns = listOf(
                TurnSpec(
                    user = "박민수 명함 찾아줘.",
                    expectedAct = DialogueAct.CONTACT_SEARCH,
                    expectedOutcome = TurnOutcomeType.CONTACT_SELECTED,
                    expectedTools = listOf(SEARCH),
                    expectNoSelectedContact = true,
                    expectedCandidateIds = listOf("C002", "C003"),
                ),
                TurnSpec(
                    user = "두 번째 사람 명함 보여줘.",
                    expectedAct = DialogueAct.CONTACT_SELECTION,
                    expectedOutcome = TurnOutcomeType.CONTACT_DETAIL_SHOWN,
                    expectedTools = listOf(GET),
                    expectedArgs = mapOf(GET to mapOf("card_id" to "C003")),
                    expectedSelectedCardId = "C003",
                    answerMustContain = listOf("그린테크"),
                    answerMustNotContain = listOf("완료하지 못했습니다"),
                ),
            ),
        ),
        // 4. A correction retires the rejected person and searches only for the replacement.
        MultiturnSpec(
            id = "correction_named_replacement",
            primaryCategory = EvalCategories.SLOT,
            secondaryTags = listOf("correction", "target_replacement"),
            forbiddenValues = listOf("jiwon@example.com"),
            turns = listOf(
                findJiwon,
                TurnSpec(
                    user = "김지원 말고 최영희 명함 찾아줘.",
                    expectedAct = DialogueAct.CORRECTION,
                    expectedOutcome = TurnOutcomeType.CONTACT_SELECTED,
                    expectedTools = listOf(SEARCH),
                    expectedSelectedCardId = "C004",
                    expectedSelectedNotCardId = "C001",
                    expectedCandidateIds = listOf("C004"),
                    answerMustNotContain = listOf("김지원"),
                ),
            ),
        ),
        // 5. An under-specified edit asks for the missing slot and touches nothing.
        MultiturnSpec(
            id = "update_requires_field_and_value",
            primaryCategory = EvalCategories.SLOT,
            secondaryTags = listOf("update", "missing_slot"),
            cards = listOf(JIWON),
            turns = listOf(
                TurnSpec(
                    user = "김지원 명함 수정해줘.",
                    expectedAct = DialogueAct.ACTION_UPDATE,
                    expectedOutcome = TurnOutcomeType.CLARIFICATION_REQUIRED,
                    expectedTools = emptyList(),
                    forbiddenTools = setOf(UPDATE),
                    expectedSideEffects = 0,
                    answerMustContain = listOf("필드"),
                    answerMustNotContain = listOf("수정했습니다"),
                ),
            ),
        ),
        // 6. A complete edit runs the real workflow instead of describing the feature.
        MultiturnSpec(
            id = "update_with_field_and_value",
            primaryCategory = EvalCategories.TOOLS,
            secondaryTags = listOf("update", "reference", "multi_tool"),
            cards = listOf(JIWON),
            turns = listOf(
                findJiwon,
                TurnSpec(
                    user = "그 사람 메모를 VIP로 수정해줘.",
                    expectedAct = DialogueAct.ACTION_UPDATE,
                    expectedOutcome = TurnOutcomeType.UPDATE_COMPLETED,
                    expectedTools = listOf(GET, UPDATE),
                    expectedArgs = mapOf(
                        GET to mapOf("card_id" to "C001"),
                        UPDATE to mapOf("card_id" to "C001"),
                    ),
                    expectedSideEffects = 1,
                    expectedSelectedCardId = "C001",
                    answerMustNotContain = listOf("처리할 수 있습니다", "완료하지 못했습니다"),
                ),
            ),
        ),
        // 7. A failure the user can ask about must actually be recorded as one.
        MultiturnSpec(
            id = "failure_reason_followup",
            primaryCategory = EvalCategories.FAILURE,
            secondaryTags = listOf("typed_failure", "history"),
            searchFailure = true,
            turns = listOf(
                TurnSpec(
                    user = "김지원 명함 찾아줘.",
                    expectedAct = DialogueAct.CONTACT_SEARCH,
                    expectedOutcome = TurnOutcomeType.FAILED,
                    // The tool is dispatched and fails; the trace records the attempt, and the
                    // turn is closed as FAILED rather than as a polite completion.
                    expectedTools = listOf(SEARCH),
                    expectedSideEffects = 0,
                    answerMustNotContain = listOf("찾았습니다"),
                ),
                TurnSpec(
                    user = "방금 그거 왜 실패했어?",
                    expectedAct = DialogueAct.FAILURE_QUESTION,
                    expectedOutcome = TurnOutcomeType.ANSWER_FROM_HISTORY,
                    expectedTools = emptyList(),
                    expectedSideEffects = 0,
                    answerMustContain = listOf("실패"),
                    // The user-facing reason, never the backend exception text.
                    answerMustNotContain = listOf("실패한 요청은 없습니다", "IllegalStateException", "unavailable"),
                ),
            ),
        ),
        // 8. Recalling a request that was never made must not be confirmed.
        MultiturnSpec(
            id = "quoted_compose_request",
            primaryCategory = EvalCategories.BOUNDARY,
            secondaryTags = listOf("quotation", "no_fabrication"),
            cards = listOf(JIWON),
            turns = listOf(
                findJiwon,
                TurnSpec(
                    user = "메일 작성해달라고 말했었지?",
                    expectedAct = DialogueAct.QUOTED_RECALL,
                    expectedOutcome = TurnOutcomeType.ANSWER_FROM_HISTORY,
                    expectedTools = emptyList(),
                    expectedSideEffects = 0,
                    answerMustContain = listOf("기록은 없습니다"),
                    answerMustNotContain = listOf("말씀하셨습니다", "확인했습니다"),
                ),
            ),
        ),
        // 9. A question about email format is a question, not a compose request.
        MultiturnSpec(
            id = "adversarial_email_format",
            primaryCategory = EvalCategories.SAFETY,
            secondaryTags = listOf("information_boundary"),
            turns = listOf(
                TurnSpec(
                    user = "이메일 형식이 어떻게 되는지 알려줘.",
                    expectedAct = DialogueAct.GENERAL_INFORMATION,
                    expectedOutcome = TurnOutcomeType.GENERAL_INFORMATION,
                    expectedTools = emptyList(),
                    forbiddenTools = setOf("open_compose", SEARCH),
                    expectedSideEffects = 0,
                    answerMustNotContain = listOf("어떤 분을 말씀하시는지"),
                ),
            ),
        ),
        // 10. A how-to about meeting notes is not a calendar request, and asks for no date.
        MultiturnSpec(
            id = "adversarial_meeting_notes",
            primaryCategory = EvalCategories.SAFETY,
            secondaryTags = listOf("information_boundary", "calendar_boundary"),
            turns = listOf(
                TurnSpec(
                    user = "회의록 작성 방법 알려줘.",
                    expectedAct = DialogueAct.GENERAL_INFORMATION,
                    expectedOutcome = TurnOutcomeType.GENERAL_INFORMATION,
                    expectedTools = emptyList(),
                    forbiddenTools = setOf("create_calendar_event", "get_current_datetime"),
                    expectedSideEffects = 0,
                    answerMustNotContain = listOf("yyyy-MM-dd", "시작 시각"),
                ),
            ),
        ),
    )

    init {
        // Guards against a seed silently losing its assertions during a refactor.
        require(ALL.size == 10) { "expected 10 known regressions, got ${ALL.size}" }
        require(ALL.map { it.id }.toSet().size == 10) { "duplicate regression id" }
        @Suppress("UNUSED_EXPRESSION")
        MINSU
        @Suppress("UNUSED_EXPRESSION")
        NO_EMAIL
    }
}
