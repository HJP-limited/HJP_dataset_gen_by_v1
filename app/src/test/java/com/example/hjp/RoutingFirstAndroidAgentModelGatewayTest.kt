package com.example.hjp

import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.tool.contact.ContactToolContracts
import com.hjp.tool.contract.ToolCatalogSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingFirstAndroidAgentModelGatewayTest {
    @Test
    fun `known tool request is handled by deterministic router before primary model`() = runBlocking {
        val primarySession = RecordingSession(ModelDecision.FinalCandidate("primary answer"))
        val primaryGateway = RecordingGateway(primarySession)
        val session = RoutingFirstAndroidAgentModelGateway(
            primary = primaryGateway,
            toolRouter = LocalToolRoutingModelGateway(),
        ).openSession(config())

        val decision = session.decide(ModelInput.User("김민수 명함 찾아줘.")) as ModelDecision.ToolCalls

        assertEquals(0, primarySession.decideCalls)
        assertEquals(0, primaryGateway.openCalls)
        assertEquals("search_contacts", decision.calls.single().modelToolName)
    }

    @Test
    fun `non tool request is handled by primary model`() = runBlocking {
        val primarySession = RecordingSession(ModelDecision.FinalCandidate("primary answer"))
        val session = RoutingFirstAndroidAgentModelGateway(
            primary = RecordingGateway(primarySession),
            toolRouter = LocalToolRoutingModelGateway(),
        ).openSession(config())

        val decision = session.decide(ModelInput.User("안녕")) as ModelDecision.FinalCandidate

        assertEquals(1, primarySession.decideCalls)
        assertEquals("primary answer", decision.draftText)
    }

    @Test
    fun `open failure falls back to deterministic router`() = runBlocking {
        val failingGateway = FailingGateway()
        val session = RoutingFirstAndroidAgentModelGateway(
            primary = failingGateway,
            toolRouter = LocalToolRoutingModelGateway(),
        ).openSession(config())

        val decision = session.decide(ModelInput.User("오늘 기분이 어때?"))

        assertTrue(decision is ModelDecision.FinalCandidate)
        assertEquals(1, failingGateway.openCalls)
    }

    private fun config() = ModelSessionConfig(
        systemInstruction = "test",
        toolCatalog = ToolCatalogSnapshot(
            revision = "r1",
            bindingRevision = "b1",
            createdAtEpochMillis = 0,
            bindings = emptyList(),
            contractsByModelName = mapOf(ContactToolContracts.Search.modelName to ContactToolContracts.Search),
        ),
        localeTag = "ko-KR",
    )
}

private class RecordingGateway(
    private val session: RecordingSession,
) : AgentModelGateway {
    var openCalls: Int = 0
        private set

    override suspend fun openSession(config: ModelSessionConfig): AgentModelSession {
        openCalls += 1
        return session
    }
}

private class FailingGateway : AgentModelGateway {
    var openCalls: Int = 0
        private set

    override suspend fun openSession(config: ModelSessionConfig): AgentModelSession {
        openCalls += 1
        throw IllegalStateException("primary unavailable")
    }
}

private class RecordingSession(
    private val decision: ModelDecision,
) : AgentModelSession {
    var decideCalls: Int = 0
        private set

    override val catalogRevision: String = "r1"

    override suspend fun decide(input: ModelInput): ModelDecision {
        decideCalls += 1
        return decision
    }

    override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision = decision

    override fun streamFinal(input: FinalAnswerInput): Flow<String> = flowOf(input.draftText)

    override fun close() = Unit
}
