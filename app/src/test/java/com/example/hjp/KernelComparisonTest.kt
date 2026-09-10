package com.example.hjp

import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.contract.AgentIntent
import com.hjp.agent.contract.ComposeContentRequest
import com.hjp.agent.contract.ConversationContext
import com.hjp.agent.contract.GeneratedComposeContent
import com.hjp.agent.contract.IntentRecipient
import com.hjp.agent.contract.RecipientType
import com.hjp.agent.contract.StructuredAgentModelGateway
import com.hjp.agent.contract.StructuredFinalRequest
import com.hjp.agent.contract.StructuredIntentPlan
import com.hjp.agent.contract.StructuredModelResult
import com.hjp.agent.core.AgentKernelMode
import com.hjp.agent.core.AgentRuntimeEnvironment
import com.hjp.agent.core.AgentTurnEngine
import com.hjp.agent.core.ContextBudget
import com.hjp.agent.core.DefaultToolExecutor
import com.hjp.agent.core.DefaultToolObservationMapper
import com.hjp.agent.core.DefaultToolPolicyEngine
import com.hjp.agent.core.DefaultToolRegistry
import com.hjp.agent.core.InMemoryAgentSessionStore
import com.hjp.agent.core.ModelContextSelector
import com.hjp.agent.core.StructuredAgentKernel
import com.hjp.agent.core.ToolImplementationCandidate
import com.hjp.tool.android.CreateCalendarEventPlugin
import com.hjp.tool.android.OpenComposePlugin
import com.hjp.tool.contact.GetContactPlugin
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.datetime.GetCurrentDateTimePlugin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs one fixture through both execution paths on the same session store, memory, context selector
 * and tools. Only the model boundary differs: the ReAct kernel drives a deterministic router, the
 * structured kernel drives a deterministic intent gateway.
 */
class KernelComparisonTest {
    private val fixture = listOf(
        "김지원 명함 찾아줘.",
        "그 사람에게 제목은 회의 안내, 내용은 잘 부탁드립니다 라고 메일 작성해줘.",
        "지금까지 찾은 사람 누구야?",
        "그 사람에게 지금 바로 실제로 메일 전송해줘.",
    )

    @Test
    fun `both kernels reach the same safe end state on the same fixture`() = runBlocking {
        val react = runReact()
        val structured = runStructured()

        assertEquals(AgentKernelMode.REACT, react.mode)
        assertEquals(AgentKernelMode.STRUCTURED, structured.mode)

        // Same recipient, taken from a re-read card rather than remembered.
        assertEquals(listOf("jiwon@example.com"), react.recipients)
        assertEquals(listOf("jiwon@example.com"), structured.recipients)

        // The conversation question runs no tool on either path.
        assertTrue(react.toolsPerTurn[2].isEmpty())
        assertTrue(structured.toolsPerTurn[2].isEmpty())

        // The unsupported request never composes on either path.
        assertEquals(1, react.recipients.size)
        assertEquals(1, structured.recipients.size)
        assertTrue(react.toolsPerTurn[3].isEmpty())
        assertTrue(structured.toolsPerTurn[3].isEmpty())
    }

    private suspend fun runReact(): Outcome {
        val harness = MultiturnScenarioHarness(cards = listOf(MultiturnScenarioHarness.JIWON))
        val tools = mutableListOf<List<String>>()
        fixture.forEach { text -> tools += harness.turn(text).toolCalls }
        val outcome = Outcome(
            mode = harness.engine.mode,
            recipients = harness.messages.drafts.map { it.to },
            toolsPerTurn = tools,
        )
        harness.close()
        return outcome
    }

    private suspend fun runStructured(): Outcome {
        val fixtureData = StructuredFixture()
        val tools = mutableListOf<List<String>>()
        fixture.forEach { text ->
            fixtureData.backend.calls.clear()
            fixtureData.engine.runTurn(text).toList()
            tools += fixtureData.backend.calls.toList()
        }
        val outcome = Outcome(
            mode = fixtureData.engine.mode,
            recipients = fixtureData.messages.drafts.map { it.to },
            toolsPerTurn = tools,
        )
        fixtureData.engine.close()
        return outcome
    }

    private data class Outcome(
        val mode: AgentKernelMode,
        val recipients: List<String>,
        val toolsPerTurn: List<List<String>>,
    )

    private class StructuredFixture {
        val repository = MultiturnScenarioHarness.RecordingRepository(
            listOf(MultiturnScenarioHarness.JIWON),
        )
        val backend = MultiturnScenarioHarness.RecordingBackend(repository, false)
        val calendar = MultiturnScenarioHarness.RecordingCalendarBackend()
        val messages = MultiturnScenarioHarness.RecordingMessageBackend()

        private val plugins = listOf(
            SearchContactsPlugin(backend),
            GetContactPlugin(backend),
            UpdateBusinessCardPlugin(repository),
            CreateCalendarEventPlugin(calendar),
            OpenComposePlugin(messages),
            GetCurrentDateTimePlugin(),
        )
        private val registry = DefaultToolRegistry(plugins.map { ToolImplementationCandidate(it) })

        val engine: AgentTurnEngine = StructuredAgentKernel(
            registry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            policyEngine = DefaultToolPolicyEngine(),
            sessionStore = InMemoryAgentSessionStore(),
            observationMapper = DefaultToolObservationMapper(),
            environment = Environment,
            modelGateway = FixtureStructuredGateway(),
            contextSelector = ModelContextSelector(ContextBudget(maxPromptTokens = 3_072)),
        )

        private object Environment : AgentRuntimeEnvironment {
            override val localeTag = "ko-KR"
            override val timeZoneId = "Asia/Seoul"
            override suspend fun grantedPermissions(): Set<String> = emptySet()
            override suspend fun deviceCapabilities(): Set<String> = setOf(
                "android.external_ui", "contact.local_search", "contact.local_update", "datetime.current",
            )

            override suspend fun toolContext(sessionId: String, turnId: String) =
                ToolExecutionContext(sessionId, turnId, localeTag, timeZoneId)
        }
    }

    /** Stands in for the Stage 1/Stage 2 model with deterministic, correct intents. */
    private class FixtureStructuredGateway : StructuredAgentModelGateway {
        override suspend fun analyzeIntent(
            userText: String,
            conversation: ConversationContext,
        ): StructuredModelResult<StructuredIntentPlan> {
            val plan = when {
                userText.contains("메일 작성") || userText.contains("메일 전송") -> plan(
                    AgentIntent.COMPOSE_EMAIL,
                    RecipientType.CONTACT_NAME,
                    "김지원",
                    "회의 안내",
                )
                userText.contains("찾아") -> plan(
                    AgentIntent.SEARCH_CONTACT, RecipientType.CONTACT_NAME, "김지원", "",
                )
                else -> plan(AgentIntent.ANSWER_ONLY, RecipientType.NONE, "", "", execute = false)
            }
            return StructuredModelResult.Success(plan, listOf("{}"), 1)
        }

        override suspend fun generateComposeContent(request: ComposeContentRequest) =
            StructuredModelResult.Success(
                GeneratedComposeContent("회의 안내", "잘 부탁드립니다."),
                listOf("{}"),
                1,
            )

        override suspend fun generateFinalText(request: StructuredFinalRequest) =
            StructuredModelResult.Success(request.completionHintKo, listOf("{}"), 1)

        private fun plan(
            intent: AgentIntent,
            recipientType: RecipientType,
            recipientValue: String,
            goal: String,
            execute: Boolean = true,
        ) = StructuredIntentPlan(
            intent,
            execute,
            IntentRecipient(recipientType, recipientValue),
            goal,
            null,
            null,
            null,
            null,
            null,
        )
    }
}
