package com.hjp.agent.core

import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.tool.contract.CatalogContext
import com.hjp.tool.contract.ConfirmationPolicy
import com.hjp.tool.contract.ContractVersion
import com.hjp.tool.contract.PiiLevel
import com.hjp.tool.contract.ToolAvailability
import com.hjp.tool.contract.ToolCapabilityId
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolEffect
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolImplementationId
import com.hjp.tool.contract.ToolPlugin
import com.hjp.tool.contract.ToolPresentation
import com.hjp.tool.contract.ToolRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentKernelTest {
    @Test
    fun `tool result is returned to model and final answer completes`() = runBlocking {
        val plugin = FakePlugin("fake.v1")
        val registry = DefaultToolRegistry(listOf(ToolImplementationCandidate(plugin)))
        val gateway = FakeGateway()
        val sessions = AgentSessionManager(InMemoryAgentSessionStore(), gateway, "system", "ko-KR")
        val kernel = AgentKernel(
            registry, DefaultToolExecutor(registry), DefaultToolPolicyEngine(), sessions,
            DefaultToolObservationMapper(), FakeEnvironment(),
        )

        val events = kernel.runTurn("검색해 줘").toList()

        assertTrue(events.any { it is AgentEvent.ToolStarted })
        assertEquals("완료", (events.last() as AgentEvent.FinalMessage).text)
        assertTrue(gateway.session.receivedResult)
        sessions.close()
    }

    @Test
    fun `implementation swap changes binding revision only`() = runBlocking {
        val first = DefaultToolRegistry(listOf(ToolImplementationCandidate(FakePlugin("fake.v1"))))
            .snapshot(CatalogContext("s", "ko-KR"))
        val second = DefaultToolRegistry(listOf(ToolImplementationCandidate(FakePlugin("fake.v2"))))
            .snapshot(CatalogContext("s", "ko-KR"))

        assertEquals(first.revision, second.revision)
        assertTrue(first.bindingRevision != second.bindingRevision)
    }

    private class FakeGateway : AgentModelGateway {
        lateinit var session: FakeModelSession
        override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
            FakeModelSession(config.toolCatalog.revision).also { session = it }
    }

    private class FakeModelSession(override val catalogRevision: String) : AgentModelSession {
        var receivedResult = false
        override suspend fun decide(input: ModelInput) = ModelDecision.ToolCalls(listOf(
            ModelToolCall("call-1", "fake_tool", buildJsonObject { put("query", "AI") })
        ))
        override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision {
            receivedResult = true
            return ModelDecision.FinalCandidate("완료")
        }
        override fun streamFinal(input: FinalAnswerInput): Flow<String> = flowOf(input.draftText)
        override fun close() = Unit
    }

    private class FakePlugin(id: String) : ToolPlugin {
        override val implementationId = ToolImplementationId(id)
        override val contract = TEST_CONTRACT
        override suspend fun availability() = ToolAvailability.Ready
        override suspend fun execute(request: ToolRequest, context: ToolExecutionContext) =
            ToolExecutionResult.Success(request.callId, request.capabilityId, request.contractVersion, 1,
                buildJsonObject { put("value", "ok") })
    }

    private class FakeEnvironment : AgentRuntimeEnvironment {
        override val localeTag = "ko-KR"
        override val timeZoneId = "Asia/Seoul"
        override suspend fun grantedPermissions() = emptySet<String>()
        override suspend fun deviceCapabilities() = emptySet<String>()
        override suspend fun toolContext(sessionId: String, turnId: String) =
            ToolExecutionContext(sessionId, turnId, localeTag, timeZoneId)
    }

    companion object {
        private val OBJECT_SCHEMA = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        }
        private val TEST_CONTRACT = ToolContract(
            ToolCapabilityId("fake.test"), "fake_tool", ContractVersion(1, 0), "fake",
            OBJECT_SCHEMA, OBJECT_SCHEMA, ToolEffect.READ_ONLY, ConfirmationPolicy.NONE,
            PiiLevel.NONE, PiiLevel.NONE, defaultTimeoutMillis = 1_000,
            presentation = ToolPresentation("실행 중", "완료", "사용 불가"),
        )
    }
}
