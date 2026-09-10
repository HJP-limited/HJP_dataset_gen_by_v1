package com.example.hjp.v5

import com.example.hjp.MultiturnScenarioHarness
import com.example.hjp.eval.v5.V5ActionProvenance
import com.example.hjp.eval.v5.V5ContactDependencyContract
import com.example.hjp.eval.v5.V5ContactStore
import com.example.hjp.eval.v5.V5TurnObservation
import com.example.hjp.eval.v5.calls
import com.example.hjp.eval.v5.derive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Observing a turn does not change it.
 *
 * v5 adds provenance: per action argument, where the value came from and what verified it. The one
 * thing that would make RUN_K5 uncomparable with RUN_K4 is if gathering that observation changed what
 * production did — a different response, a different tool, a different argument, a different target.
 * Then the two runs would be measurements of two different agents and the comparison would be
 * meaningless.
 *
 * So this drives the real production wiring twice over the same sentences: once plainly, and once
 * with the provenance derivation running after every turn. The two traces must be identical, field by
 * field.
 *
 * The derivation is pure — it reads an immutable observation and returns a value — but "it is pure"
 * is an argument, and this is a measurement.
 */
class V5ProvenanceNonInvasivenessTest {

    private val script = listOf(
        "김지원 회사 알려줘",
        "이메일 주소는?",
        "그 사람한테 메일 초안 하나 써줘",
        "내일 3시에 일정 잡아줘",
        "박민수는 어디 소속이야",
        "메모에 재검토라고 남겨줘",
    )

    private data class Trace(
        val answer: String,
        val tools: List<String>,
        val arguments: List<String>,
        val outcome: String?,
        val act: String,
        val selected: String?,
        val composed: Int,
        val calendar: Int,
    )

    @Test
    fun `deriving provenance changes no response, tool, argument or target`() {
        val plain = run(observe = false)
        val observed = run(observe = true)

        assertEquals("turn count", plain.size, observed.size)
        plain.indices.forEach { index ->
            assertEquals("turn ${index + 1}: '${script[index]}'", plain[index], observed[index])
        }
    }

    @Test
    fun `the observation the derivation reads is the same object afterwards`() {
        val contract = V5ContactDependencyContract.load()
        val store = V5ContactStore.fromRecords(MultiturnScenarioHarness.DEFAULT_CARDS)
        val harness = MultiturnScenarioHarness()
        try {
            runBlocking {
                script.forEachIndexed { index, sentence ->
                    val record = harness.turn(sentence)
                    val turn = observationOf(record, index + 1, harness)
                    val before = turn.copy()
                    V5ActionProvenance.derive(turn, store, contract)
                    assertEquals("the derivation mutated its input", before, turn)
                    assertEquals(
                        "the derivation changed the flattened calls",
                        before.calls(), turn.calls(),
                    )
                }
            }
        } finally {
            harness.close()
        }
    }

    private fun run(observe: Boolean): List<Trace> {
        val contract = if (observe) V5ContactDependencyContract.load() else null
        val store = if (observe) V5ContactStore.fromRecords(MultiturnScenarioHarness.DEFAULT_CARDS) else null
        val harness = MultiturnScenarioHarness()
        return try {
            runBlocking {
                script.mapIndexed { index, sentence ->
                    val record = harness.turn(sentence)
                    if (observe) {
                        V5ActionProvenance.derive(
                            observationOf(record, index + 1, harness), store!!, contract!!,
                        )
                    }
                    Trace(
                        answer = record.answer,
                        tools = record.executedTools,
                        arguments = record.toolArguments.map { "${it.first}:${it.second}" },
                        outcome = record.outcomeType?.name,
                        act = record.act.name,
                        selected = record.memory.selectedContact?.cardId,
                        composed = record.composed.size,
                        calendar = record.calendarDrafts.size,
                    )
                }
            }
        } finally {
            harness.close()
        }
    }

    private suspend fun observationOf(
        record: MultiturnScenarioHarness.TurnRecord,
        depth: Int,
        harness: MultiturnScenarioHarness,
    ) = V5TurnObservation(
        scenarioIndex = 0,
        depth = depth,
        kind = "parity",
        question = record.text,
        expectedRoute = null,
        noCardsOutOfScope = false,
        outcomeType = record.outcomeType?.name,
        answer = record.answer,
        executedTools = record.executedTools,
        toolArguments = record.toolArguments,
        selectedCardId = record.memory.selectedContact?.cardId,
        previousFocusCardId = null,
        utteranceReferencesPreviousFocus = false,
        unverifiedTargetUses = emptyList(),
        leakedIntoActionArguments = emptyList(),
        sessionGeneration = harness.session().generation,
    )
}
