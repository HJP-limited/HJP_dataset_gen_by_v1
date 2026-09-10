package com.example.hjp.v4

import com.example.hjp.LocalToolRoutingModelGateway
import com.example.hjp.v4char.V4Cards
import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentRuntimeCounters
import com.hjp.agent.contract.CountingAgentModelGateway
import com.hjp.agent.contract.ModelBoundaryKind
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.RuntimeCounterSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four properties that make the runtime counters usable as evidence.
 *
 * 1. **They do not change the run.** A number that alters what it measures is not a measurement.
 * 2. **They cannot carry personal data.** The snapshot is counts and durations, so an evidence file
 *    can contain it verbatim without a redaction step nobody would notice was missing.
 * 3. **"An actual model ran" is derived, never asserted.** The deterministic gateway answers every
 *    turn; it is not a language model, and the counter that says one ran must stay at zero for it.
 * 4. **Per-turn records sum to the aggregate.** Otherwise "the totals disagree with the turns" is
 *    undetectable, which is precisely the class of defect the counters exist to expose.
 */
class RuntimeCounterContractTest {

    /** A deterministic gateway that claims — falsely — to be a real model, for the derivation test. */
    private class PretendModelGateway(
        private val delegate: AgentModelGateway,
    ) : AgentModelGateway by delegate {
        override val boundaryKind = ModelBoundaryKind.ACTUAL_MODEL
    }

    private val script = listOf(
        "차솔빈 명함 찾아줘.",
        "그 사람에게 제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘.",
        "오늘 날짜 알려줘.",
        "봉예람 명함 찾아줘.",
        "그분에게 도착했다고 문자 작성해줘.",
    )

    private data class Observed(
        val answers: List<String>,
        val tools: List<List<String>>,
        val outcomes: List<String?>,
        val composed: List<String>,
    )

    private suspend fun run(gateway: AgentModelGateway?): Pair<Observed, AgentRuntimeCounters> {
        val counters = AgentRuntimeCounters()
        val h = MultiturnScenarioHarness(
            cards = listOf(V4Cards.ANCHOR, V4Cards.SECOND),
            modelOverride = gateway,
        )
        val answers = mutableListOf<String>()
        val tools = mutableListOf<List<String>>()
        val outcomes = mutableListOf<String?>()
        script.forEach { text ->
            val turn = h.turn(text)
            answers += turn.answer
            tools += turn.executedTools
            outcomes += turn.outcomeType?.name
        }
        val observed = Observed(answers, tools, outcomes, h.messages.drafts.map { it.to })
        h.close()
        return observed to counters
    }

    @Test
    fun `counting changes nothing about the run`() = runBlocking {
        val counters = AgentRuntimeCounters()
        val plain = run(null).first
        val counted = run(CountingAgentModelGateway(LocalToolRoutingModelGateway(), counters)).first

        assertEquals("answers differ", plain.answers, counted.answers)
        assertEquals("tool traces differ", plain.tools, counted.tools)
        assertEquals("typed outcomes differ", plain.outcomes, counted.outcomes)
        assertEquals("compose recipients differ", plain.composed, counted.composed)
        // And it did observe something, or the comparison above proves nothing.
        assertTrue(counters.snapshot().boundaryRequests > 0)
    }

    @Test
    fun `a deterministic boundary never counts as an actual model`() = runBlocking {
        val counters = AgentRuntimeCounters()
        val h = MultiturnScenarioHarness(
            cards = listOf(V4Cards.ANCHOR),
            modelOverride = CountingAgentModelGateway(LocalToolRoutingModelGateway(), counters),
        )
        script.forEach { h.turn(it) }
        h.close()

        val snapshot = counters.snapshot()
        assertTrue("the boundary was never asked anything", snapshot.boundaryRequests > 0)
        assertTrue("the boundary produced no decisions", snapshot.boundaryDecisions > 0)
        assertEquals(0, snapshot.modelInvocationAttempts)
        assertEquals(0, snapshot.modelInvocationSuccesses)
        assertEquals(0, snapshot.modelLatencySamples)
        assertFalse(snapshot.actualModelExecuted)
        assertFalse(snapshot.actualSemanticExecuted)
        assertNull("there is no mean of no samples", snapshot.modelLatencyMeanMillis)
    }

    @Test
    fun `a boundary that declares itself an actual model is counted as one`() = runBlocking {
        val counters = AgentRuntimeCounters()
        val h = MultiturnScenarioHarness(
            cards = listOf(V4Cards.ANCHOR),
            modelOverride = CountingAgentModelGateway(
                PretendModelGateway(LocalToolRoutingModelGateway()), counters,
            ),
        )
        script.forEach { h.turn(it) }
        h.close()

        val snapshot = counters.snapshot()
        assertTrue(snapshot.modelInvocationAttempts > 0)
        assertEquals(
            "every attempt that returned must be a success",
            snapshot.modelInvocationAttempts,
            snapshot.modelInvocationSuccesses + snapshot.modelInvocationFailures +
                snapshot.modelInvocationCancellations,
        )
        assertEquals(snapshot.modelInvocationSuccesses, snapshot.modelLatencySamples)
        assertTrue(snapshot.actualModelExecuted)
    }

    @Test
    fun `per-turn deltas sum to the aggregate`() = runBlocking {
        val counters = AgentRuntimeCounters()
        val h = MultiturnScenarioHarness(
            cards = listOf(V4Cards.ANCHOR, V4Cards.SECOND),
            modelOverride = CountingAgentModelGateway(LocalToolRoutingModelGateway(), counters),
        )
        var previous = counters.snapshot()
        val perTurn = mutableListOf<RuntimeCounterSnapshot>()
        script.forEach { text ->
            h.turn(text)
            val now = counters.snapshot()
            perTurn += now - previous
            previous = now
        }
        h.close()

        val aggregate = counters.snapshot().asLongMap()
        val summed = RuntimeCounterSnapshot.FIELD_NAMES.associateWith { field ->
            perTurn.sumOf { it.asLongMap().getValue(field) }
        }
        // Maxima are not additive and say so in their own contract; everything else must reconcile.
        val additive = RuntimeCounterSnapshot.FIELD_NAMES.filterNot { it.endsWith("_max_millis") }
        additive.forEach { field ->
            assertEquals(
                "aggregate and per-turn records disagree on $field",
                aggregate.getValue(field), summed.getValue(field),
            )
        }
        assertTrue("no turn was observed at all", perTurn.any { it.boundaryRequests > 0 })
    }

    @Test
    fun `the snapshot cannot carry anything but numbers`() {
        val instanceFields = RuntimeCounterSnapshot::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        val nonNumeric = instanceFields
            .filterNot { it.type == java.lang.Long.TYPE || it.type == java.lang.Long::class.java }
            .map { it.name }
        assertEquals(
            "a non-numeric field would let user text into an evidence file: $nonNumeric",
            emptyList<String>(), nonNumeric,
        )
        assertEquals(
            "the serialisation map must cover every field",
            instanceFields.size, RuntimeCounterSnapshot.FIELD_NAMES.size,
        )
    }

    @Test
    fun `the kernel separates deterministic turns from boundary turns`() = runBlocking {
        val h = MultiturnScenarioHarness(cards = listOf(V4Cards.ANCHOR))
        // A refusal and a recall are settled by the router alone; a lookup goes to the boundary.
        listOf(
            "명함 지워줘.",                      // capability veto -> Unsupported
            "제가 메일 보내달라고 했었나요?",      // recall -> AnswerFromHistory
            "차솔빈 명함 찾아줘.",                // -> the boundary
        ).forEach { h.turn(it) }
        val snapshot = h.kernel.runtimeCounters.snapshot()
        h.close()

        val shape = "turns=${snapshot.turnsStarted} det=${snapshot.deterministicOnlyTurns} " +
            "boundary=${snapshot.modelBoundaryTurns}"
        assertEquals(shape, 3, snapshot.turnsStarted.toInt())
        assertEquals(
            "every turn is either deterministic or reaches the boundary, exactly once: $shape",
            snapshot.turnsStarted,
            snapshot.deterministicOnlyTurns + snapshot.modelBoundaryTurns,
        )
        assertTrue(shape, snapshot.deterministicOnlyTurns > 0)
        assertTrue(shape, snapshot.modelBoundaryTurns > 0)
    }

    @Test
    fun `a completed side effect is counted once`() = runBlocking {
        val h = MultiturnScenarioHarness(cards = listOf(V4Cards.ANCHOR))
        h.turn("차솔빈 명함 찾아줘.")
        h.turn("그 사람에게 제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘.")
        val snapshot = h.kernel.runtimeCounters.snapshot()
        h.close()
        assertEquals(1, snapshot.terminalActionCompletions)
    }
}
