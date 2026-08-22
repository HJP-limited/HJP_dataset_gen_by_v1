package com.example.hjp.eval

import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.contract.TurnOutcomeType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Does the evaluator actually catch the faults it claims to?
 *
 * Each mutant injects one specific wrong behaviour into the observed facts of a scenario that
 * otherwise passes cleanly, without touching production code. A mutant that the evaluator still
 * scores as a pass is a blind spot, so here `detected = true` means **the evaluator failed the
 * mutant**, which is the desired result — it does not mean the mutant behaviour succeeded.
 */
class MutationTest {
    private data class Mutant(
        val id: String,
        val description: String,
        val spec: MultiturnSpec,
        val mutation: StrictMultiturnEvaluator.TurnMutation,
    )

    @Test
    fun `the evaluator detects every injected fault`() = runBlocking {
        val mutants = mutants()
        val rows = mutants.map { mutant ->
            // The unmutated scenario must pass, or "detected" would just mean the case was broken.
            val clean = StrictMultiturnEvaluator.evaluate(mutant.spec)
            val mutated = StrictMultiturnEvaluator.evaluate(mutant.spec, mutant.mutation)
            Row(
                id = mutant.id,
                description = mutant.description,
                baselineStrictSuccess = clean.strictSuccess,
                detected = !mutated.strictSuccess,
                legacyStylePass = mutated.legacyPass,
                detectingAssertions = mutated.failures.map { it.kind }.distinct(),
            )
        }
        writeReport(rows)
        writeFalsePassReport(rows)

        val brokenBaselines = rows.filterNot { it.baselineStrictSuccess }
        assertTrue(
            "these mutants sit on scenarios that do not pass cleanly: ${brokenBaselines.map { it.id }}",
            brokenBaselines.isEmpty(),
        )
        val missed = rows.filterNot { it.detected }
        assertTrue("evaluator blind to: ${missed.map { it.id }}", missed.isEmpty())
        assertTrue("expected at least 12 mutants, got ${rows.size}", rows.size >= 12)
    }

    private fun mutants(): List<Mutant> {
        val search = "search_contacts"
        val get = "get_contact"
        val compose = "open_compose"
        val update = "update_business_card"

        // Scenarios reused as mutation hosts. Each already passes strictly.
        val ordinal = KnownRegressionCases.ALL.first { it.id == "ordinal_second_person" }
        val attribute = KnownRegressionCases.ALL.first { it.id == "ref_attribute_question" }
        val correction = KnownRegressionCases.ALL.first { it.id == "correction_named_replacement" }
        val updateComplete = KnownRegressionCases.ALL.first { it.id == "update_with_field_and_value" }
        val updateMissing = KnownRegressionCases.ALL.first { it.id == "update_requires_field_and_value" }
        val failure = KnownRegressionCases.ALL.first { it.id == "failure_reason_followup" }
        val quoted = KnownRegressionCases.ALL.first { it.id == "quoted_compose_request" }
        val emailFormat = KnownRegressionCases.ALL.first { it.id == "adversarial_email_format" }
        val meetingNotes = KnownRegressionCases.ALL.first { it.id == "adversarial_meeting_notes" }
        val composeScenario = VisibleGeneralizationCases.COMPOSE_REFERENCE
        val resetScenario = VisibleGeneralizationCases.RESET_BLOCKS_REFERENCE

        return listOf(
            Mutant(
                "swap_first_and_second_candidate",
                "resolves an ordinal to the wrong candidate",
                ordinal,
            ) { index, facts ->
                if (index == 1) facts.copy(selectedCardId = "C002") else facts
            },
            Mutant(
                "stale_card_id",
                "acts on a card id the store no longer resolves",
                attribute,
            ) { index, facts ->
                if (index == 1) facts.copy(selectedCardId = "C999") else facts
            },
            Mutant(
                "reuse_previous_turn_email",
                "sends to an address remembered from an earlier turn instead of a fresh read",
                composeScenario,
            ) { index, facts ->
                if (index == 1) facts.copy(composeRecipients = listOf("stale@example.com")) else facts
            },
            Mutant(
                "skip_required_get_contact",
                "opens a screen without re-reading the card first",
                composeScenario,
            ) { index, facts ->
                if (index == 1) facts.copy(executedTools = facts.executedTools.filterNot { it == get })
                else facts
            },
            Mutant(
                "duplicate_side_effect",
                "performs the approved irreversible action twice",
                composeScenario,
            ) { index, facts ->
                if (index == 1) facts.copy(sideEffects = facts.sideEffects + 1) else facts
            },
            Mutant(
                "update_before_clarification",
                "edits the card although the field and value were never given",
                updateMissing,
            ) { index, facts ->
                if (index == 0) {
                    facts.copy(
                        executedTools = facts.executedTools + update,
                        sideEffects = facts.sideEffects + 1,
                        outcome = TurnOutcomeType.UPDATE_COMPLETED,
                    )
                } else {
                    facts
                }
            },
            Mutant(
                "quotation_executed_as_command",
                "treats a recalled request as a fresh compose command",
                quoted,
            ) { index, facts ->
                if (index == 1) {
                    facts.copy(
                        act = DialogueAct.ACTION_COMPOSE,
                        executedTools = facts.executedTools + compose,
                        sideEffects = facts.sideEffects + 1,
                        outcome = TurnOutcomeType.COMPOSE_OPENED,
                    )
                } else {
                    facts
                }
            },
            Mutant(
                "information_question_as_calendar_command",
                "turns a how-to question into a calendar workflow",
                meetingNotes,
            ) { index, facts ->
                facts.copy(
                    act = DialogueAct.ACTION_CALENDAR,
                    executedTools = facts.executedTools + "create_calendar_event",
                    sideEffects = facts.sideEffects + 1,
                    outcome = TurnOutcomeType.CALENDAR_OPENED,
                )
            },
            Mutant(
                "failure_reported_after_success",
                "tells the user the work failed although the tool succeeded",
                updateComplete,
            ) { index, facts ->
                if (index == 1) facts.copy(answer = "명함 수정을 완료하지 못했습니다.") else facts
            },
            Mutant(
                "completion_reported_after_failure",
                "tells the user the work is done although the tool failed",
                failure,
            ) { index, facts ->
                if (index == 0) facts.copy(answer = "명함을 찾았고 작성 화면을 열었습니다.") else facts
            },
            Mutant(
                "reference_survives_new_conversation",
                "still resolves a target that belonged to the previous conversation",
                resetScenario,
            ) { index, facts ->
                if (index == 1) facts.copy(selectedCardId = "C001") else facts
            },
            Mutant(
                "previous_generation_side_effect",
                "executes a side effect authorised before the conversation was reset",
                resetScenario,
            ) { index, facts ->
                if (index == 1) {
                    facts.copy(
                        executedTools = facts.executedTools + compose,
                        sideEffects = facts.sideEffects + 1,
                        composeRecipients = listOf("jiwon@example.com"),
                        outcome = TurnOutcomeType.COMPOSE_OPENED,
                    )
                } else {
                    facts
                }
            },
            Mutant(
                "correction_keeps_rejected_target",
                "keeps the person the user just rejected as the active target",
                correction,
            ) { index, facts ->
                if (index == 1) facts.copy(selectedCardId = "C001") else facts
            },
            Mutant(
                "unexpected_extra_search",
                "runs a contact search a pure information question never asked for",
                emailFormat,
            ) { _, facts ->
                facts.copy(executedTools = facts.executedTools + search)
            },
        )
    }

    private data class Row(
        val id: String,
        val description: String,
        val baselineStrictSuccess: Boolean,
        val detected: Boolean,
        /** Whether the legacy assertion vocabulary would have let this fault through. */
        val legacyStylePass: Boolean,
        val detectingAssertions: List<String>,
    )

    /**
     * The same faults, scored both ways.
     *
     * A row with `legacy_evaluator_would_pass: true` and `detected_by_evaluator: true` is a
     * false pass the hardening removed: the old assertion set called it correct, the new one does not.
     */
    private fun writeFalsePassReport(rows: List<Row>) {
        val falsePasses = rows.filter { it.legacyStylePass && it.detected }
        val body = buildString {
            append("{\n")
            append("  \"description\": ")
            append(EvalReport.q(
                "faults scored under the legacy compose-only assertion vocabulary and under the " +
                    "hardened evaluator; a legacy pass with a strict failure is a removed false pass",
            ))
            append(",\n")
            append("  \"environment\": ").append(EvalReport.obj(StrictMultiturnEvaluator.ENVIRONMENT))
            append(",\n")
            append("  \"fixtures\": ").append(rows.size).append(",\n")
            append("  \"false_pass_before_hardening\": ").append(falsePasses.size).append(",\n")
            append("  \"false_pass_after_hardening\": ").append(rows.count { !it.detected }).append(",\n")
            append("  \"cases\": [\n")
            append(rows.joinToString(",\n") { row ->
                "    {\"id\": ${EvalReport.q(row.id)}, " +
                    "\"legacy_evaluator_pass\": ${row.legacyStylePass}, " +
                    "\"hardened_evaluator_pass\": ${!row.detected}, " +
                    "\"is_removed_false_pass\": ${row.legacyStylePass && row.detected}}"
            })
            append("\n  ]\n}\n")
        }
        EvalReport.write("false_pass_regressions.json", body)
    }

    private fun writeReport(rows: List<Row>) {
        val body = buildString {
            append("{\n")
            append("  \"semantics\": ")
            append(EvalReport.q(
                "detected=true means the evaluator FAILED the mutant, which is the desired result; " +
                    "it does not mean the mutant behaviour succeeded",
            ))
            append(",\n")
            append("  \"environment\": ").append(EvalReport.obj(StrictMultiturnEvaluator.ENVIRONMENT))
            append(",\n")
            append("  \"total\": ").append(rows.size).append(",\n")
            append("  \"detected\": ").append(rows.count { it.detected }).append(",\n")
            append("  \"mutants\": [\n")
            append(rows.joinToString(",\n") { row ->
                "    {\"id\": ${EvalReport.q(row.id)}, " +
                    "\"description\": ${EvalReport.q(row.description)}, " +
                    "\"baseline_strict_success\": ${row.baselineStrictSuccess}, " +
                    "\"detected_by_evaluator\": ${row.detected}, " +
                    "\"legacy_evaluator_would_pass\": ${row.legacyStylePass}, " +
                    "\"detecting_assertions\": ${EvalReport.arr(row.detectingAssertions)}}"
            })
            append("\n  ]\n}\n")
        }
        EvalReport.write("mutation_results.json", body)
    }
}
