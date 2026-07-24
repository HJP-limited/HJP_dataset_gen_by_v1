package com.hjp.desktop

import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.agent.routing.GroundedToolResultFormatter
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DesktopAgentIntegrationTest {
    @Test
    fun `router to registry to real contact search to grounded final answer`() = runBlocking {
        val config = config(RunnerMode.ROUTER_ONLY)
        val source = DesktopFileDataSource(config.cardDataFile)
        val existing = source.loadAll().first()

        DesktopAgentRunner(config).use { runner ->
            runner.validateData()
            val final = runner.runPrompt("${existing.name} 명함 찾아줘.")
            assertTrue(final.contains(existing.name))
            if (existing.company.isNotBlank()) assertTrue(final.contains(existing.company))
        }
    }

    @Test
    fun `missing real name returns not-found without inventing a card`() = runBlocking {
        val config = config(RunnerMode.ROUTER_ONLY)
        val source = DesktopFileDataSource(config.cardDataFile)
        val cards = source.loadAll()
        val missing = generateSequence("테스트미존재인물") { "${it}X" }.first { name -> cards.none { it.name == name } }

        DesktopAgentRunner(config).use { runner ->
            runner.validateData()
            val final = runner.runPrompt("$missing 명함 찾아줘.")
            assertTrue(final.contains("찾지 못했습니다"))
            assertFalse(cards.any { final.contains(it.phone) && it.phone.isNotBlank() })
        }
    }

    @Test
    fun `model failure does not kill full pipeline and deterministic fallback remains`() = runBlocking {
        val config = config(RunnerMode.FULL).copy(
            liteRtBin = File("/missing/litert-lm"),
            modelFile = File("/missing/model.litertlm"),
        )

        DesktopAgentRunner(config).use { runner ->
            runner.validateData()
            val final = runner.runPrompt("안녕")
            assertTrue(final.contains("명함 검색"))
        }
    }

    @Test
    fun `model-agent bypasses deterministic router and executes model-originated tool call`() = runBlocking {
        val config = config(RunnerMode.MODEL_AGENT)
        val existing = DesktopFileDataSource(config.cardDataFile).loadAll().first()
        val traces = mutableListOf<Pair<String, String>>()
        val fake = ScriptedGateway { input ->
            ModelDecision.ToolCalls(listOf(ModelToolCall(
                callId = "model-call-1",
                modelToolName = "search_contacts",
                arguments = buildJsonObject { put("query", existing.name) },
            )))
        }

        DesktopAgentRunner(
            config = config,
            modelAgentGatewayOverride = fake,
            traceObserver = { stage, message -> traces += stage to message },
            printOutput = false,
        ).use { runner ->
            runner.validateData()
            val final = runner.runPrompt("${existing.name} 명함 찾아줘.")
            assertTrue(final.contains(existing.name))
        }

        assertEquals(1, fake.decideCount)
        assertTrue(traces.any { it.first == "ROUTING_POLICY" && it.second.contains("bypassed=true") })
        assertTrue(traces.any { it.first == "PARSED_TOOL_CALL" && it.second.contains("search_contacts") })
        assertTrue(traces.any { it.first == "TOOL_RESULT" })
        assertFalse(traces.any { it.first == "ROUTER" })
    }

    @Test
    fun `model-agent no-tool decision does not execute a tool`() = runBlocking {
        val traces = mutableListOf<Pair<String, String>>()
        val fake = ScriptedGateway { ModelDecision.FinalCandidate("저는 차분한 상태예요.") }
        DesktopAgentRunner(
            config = config(RunnerMode.MODEL_AGENT),
            modelAgentGatewayOverride = fake,
            traceObserver = { stage, message -> traces += stage to message },
            printOutput = false,
        ).use { runner ->
            runner.validateData()
            assertEquals("저는 차분한 상태예요.", runner.runPrompt("오늘 기분이 어때?"))
        }
        assertFalse(traces.any { it.first == "TOOL_EXECUTION" })
    }

    private fun config(mode: RunnerMode): DesktopConfig {
        val root = projectRoot()
        return DesktopConfig(
            mode = mode,
            prompt = null,
            debug = false,
            liteRtBin = File(root, "missing-bin"),
            modelId = "gemma3-1b-it-int4",
            modelFile = File(root, "missing-model"),
            backend = "cpu",
            cardDataFile = File(root, "app/src/main/assets/cards/business_cards.json"),
            timeoutMillis = 5_000,
            projectRoot = root,
        )
    }

    private fun projectRoot(): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
        }
        error("project root not found")
    }
}

private class ScriptedGateway(
    private val decision: (ModelInput.User) -> ModelDecision,
) : AgentModelGateway {
    var decideCount: Int = 0

    override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
        object : AgentModelSession {
            override val catalogRevision: String = config.toolCatalog.revision

            override suspend fun decide(input: ModelInput): ModelDecision {
                decideCount += 1
                return decision(input as ModelInput.User)
            }

            override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision =
                GroundedToolResultFormatter.finish(result)

            override fun streamFinal(input: FinalAnswerInput): Flow<String> =
                flowOf(input.draftText)

            override fun close() = Unit
        }
}
