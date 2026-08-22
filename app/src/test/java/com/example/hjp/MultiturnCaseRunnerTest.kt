package com.example.hjp

import com.example.hjp.eval.EvalReport
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * Runs the whole multiturn case set and writes a per-case record, not just an aggregate.
 *
 * Environment: fake gateway (the deterministic router stands in for the model) over the real
 * kernel, router, policy engine, workflow and plugins. These results say nothing about Gemma's
 * own behaviour; they verify the orchestration and safety layer.
 */
class MultiturnCaseRunnerTest {
    @Test
    fun `every multiturn case holds its safety property`() = runBlocking {
        val records = MultiturnCases.ALL.map { case -> run(case) }
        writeReport(records)

        val failed = records.filterNot { it.passed }
        assertTrue(
            "failed ${failed.size}/${records.size}:\n" +
                failed.joinToString("\n") { "- ${it.id}: ${it.failureReason}" },
            failed.isEmpty(),
        )
        assertTrue("at least 40 cases required, got ${records.size}", records.size >= 40)
    }

    private suspend fun run(case: MultiturnCase): CaseRecord {
        val harness = MultiturnScenarioHarness(
            cards = case.cards,
            searchFailure = case.searchFailure,
            confirmUpdates = case.confirmUpdates,
        )
        val toolTrace = mutableListOf<String>()
        var lastAnswer = ""
        var lastTurnTools = emptyList<String>()
        case.turns.forEachIndexed { index, text ->
            if (case.resetBeforeTurn == index) harness.reset()
            val record = harness.turn(text)
            lastAnswer = record.answer
            lastTurnTools = record.executedTools
            toolTrace += lastTurnTools
        }
        val session = harness.session()
        val memory = session.conversationMemory
        val composed = harness.messages.drafts.map { it.to }
        val failures = mutableListOf<String>()

        case.expectedToolTrace?.let {
            if (toolTrace != it) failures += "tool trace $toolTrace != $it"
        }
        case.expectedComposeTo?.let {
            if (composed.lastOrNull() != it) failures += "compose recipient ${composed.lastOrNull()} != $it"
        }
        if (case.expectNoCompose && composed.isNotEmpty()) {
            failures += "compose ran with $composed"
        }
        if (case.expectNoToolsOnLastTurn && lastTurnTools.isNotEmpty()) {
            failures += "last turn ran $lastTurnTools"
        }
        case.expectAnswerContains?.let {
            if (!lastAnswer.contains(it)) failures += "answer missing '$it': $lastAnswer"
        }
        case.expectAnswerExcludes?.let {
            if (lastAnswer.contains(it)) failures += "answer must not contain '$it'"
        }
        case.expectedSelectedNotCardId?.let {
            if (memory.selectedContact?.cardId == it) failures += "rejected target $it is still selected"
        }
        case.expectedSelectedCardId?.let {
            if (memory.selectedContact?.cardId != it) {
                failures += "selected ${memory.selectedContact?.cardId} != $it"
            }
        }
        if (case.expectNoSelectedContact && memory.selectedContact != null) {
            failures += "selected contact should be cleared but was ${memory.selectedContact?.cardId}"
        }
        // Invariants that hold for every case, regardless of its own expectations.
        if (composed.any { it.isBlank() }) failures += "composed to a blank recipient"
        // An address the user typed is theirs; an address that only a tool result knew must never
        // appear in the prompt.
        val userProvided = case.turns.flatMap { EMAIL_REGEX.findAll(it).map(MatchResult::value) }.toSet()
        val leaked = harness.gateway.prompts
            .flatMap { EMAIL_REGEX.findAll(it).map(MatchResult::value) }
            .filterNot { it in userProvided }
        if (leaked.isNotEmpty()) failures += "tool-sourced address reached the prompt: $leaked"

        val record = CaseRecord(
            id = case.id,
            category = case.category,
            turns = case.turns,
            resetBeforeTurn = case.resetBeforeTurn,
            expectedToolTrace = case.expectedToolTrace,
            expectedComposeTo = case.expectedComposeTo,
            expectNoCompose = case.expectNoCompose,
            expectNoToolsOnLastTurn = case.expectNoToolsOnLastTurn,
            expectedSelectedCardId = case.expectedSelectedCardId ?: case.expectedSelectedNotCardId?.let { "not:$it" },
            actualToolTrace = toolTrace,
            actualComposeRecipients = composed,
            actualSelectedCardId = memory.selectedContact?.cardId,
            actualCandidateIds = memory.candidateContacts.map { it.cardId },
            actualMentionIds = memory.contactMentions.map { it.cardId },
            actualActionStatuses = memory.actions.map { it.status.name },
            finalAnswer = lastAnswer,
            passed = failures.isEmpty(),
            failureReason = failures.joinToString("; ").ifEmpty { null },
        )
        harness.close()
        return record
    }

    private fun writeReport(records: List<CaseRecord>) {
        val byCategory = records.groupBy { it.category }
            .mapValues { (_, list) -> list.count { it.passed } to list.size }
        val body = buildString {
            append("{\n  \"environment\": \"fake_gateway_deterministic_router\",\n")
            append("  \"total\": ${records.size},\n")
            append("  \"passed\": ${records.count { it.passed }},\n")
            append("  \"by_category\": {\n")
            append(byCategory.entries.joinToString(",\n") { (name, score) ->
                "    \"$name\": {\"passed\": ${score.first}, \"total\": ${score.second}}"
            })
            append("\n  },\n  \"cases\": [\n")
            append(records.joinToString(",\n") { it.toJson() })
            append("\n  ]\n}\n")
        }
        // Written through the policy, not to an absolute historical path. This test used to
        // overwrite results/multiturn_cases.json, the record of an earlier evaluation, every time it
        // ran.
        EvalReport.write("multiturn_cases.json", body)
    }

    private data class CaseRecord(
        val id: String,
        val category: String,
        val turns: List<String>,
        val resetBeforeTurn: Int?,
        val expectedToolTrace: List<String>?,
        val expectedComposeTo: String?,
        val expectNoCompose: Boolean,
        val expectNoToolsOnLastTurn: Boolean,
        val expectedSelectedCardId: String?,
        val actualToolTrace: List<String>,
        val actualComposeRecipients: List<String>,
        val actualSelectedCardId: String?,
        val actualCandidateIds: List<String>,
        val actualMentionIds: List<String>,
        val actualActionStatuses: List<String>,
        val finalAnswer: String,
        val passed: Boolean,
        val failureReason: String?,
    ) {
        fun toJson(): String = buildString {
            append("    {")
            append("\"id\": ").append(quote(id)).append(", ")
            append("\"category\": ").append(quote(category)).append(", ")
            append("\"turns\": ").append(array(turns)).append(", ")
            append("\"reset_before_turn\": ").append(resetBeforeTurn?.toString() ?: "null").append(", ")
            append("\"expected_tool_trace\": ").append(expectedToolTrace?.let(::array) ?: "null").append(", ")
            append("\"expected_compose_to\": ").append(quoteOrNull(expectedComposeTo)).append(", ")
            append("\"expect_no_compose\": ").append(expectNoCompose).append(", ")
            append("\"expect_no_tools_on_last_turn\": ").append(expectNoToolsOnLastTurn).append(", ")
            append("\"expected_selected_card_id\": ").append(quoteOrNull(expectedSelectedCardId)).append(", ")
            append("\"actual_tool_trace\": ").append(array(actualToolTrace)).append(", ")
            append("\"actual_compose_recipients\": ").append(array(actualComposeRecipients)).append(", ")
            append("\"actual_selected_card_id\": ").append(quoteOrNull(actualSelectedCardId)).append(", ")
            append("\"actual_candidate_ids\": ").append(array(actualCandidateIds)).append(", ")
            append("\"actual_mention_ids\": ").append(array(actualMentionIds)).append(", ")
            append("\"actual_action_statuses\": ").append(array(actualActionStatuses)).append(", ")
            append("\"final_answer\": ").append(quote(finalAnswer)).append(", ")
            append("\"passed\": ").append(passed).append(", ")
            append("\"failure_reason\": ").append(quoteOrNull(failureReason))
            append("}")
        }

        private fun array(values: List<String>) = values.joinToString(", ", "[", "]", transform = ::quote)

        private fun quoteOrNull(value: String?) = value?.let(::quote) ?: "null"

        private fun quote(value: String) = "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "") + "\""
    }

    private companion object {
        val EMAIL_REGEX = Regex("[A-Za-z0-9._-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    }
}
