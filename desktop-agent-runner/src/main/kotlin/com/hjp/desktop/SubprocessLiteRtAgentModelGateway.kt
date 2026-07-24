package com.hjp.desktop

import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.agent.routing.GroundedToolResultFormatter
import com.hjp.agent.routing.ModelToolCallParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

fun interface DesktopTraceSink {
    fun log(stage: String, message: String)
}

class SubprocessLiteRtAgentModelGateway(
    private val runner: ModelTextRunner,
    private val trace: DesktopTraceSink = DesktopTraceSink { _, _ -> },
) : AgentModelGateway {
    override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
        SubprocessLiteRtAgentModelSession(config, runner, trace)
}

private class SubprocessLiteRtAgentModelSession(
    private val config: ModelSessionConfig,
    private val runner: ModelTextRunner,
    private val trace: DesktopTraceSink,
) : AgentModelSession {
    override val catalogRevision: String = config.toolCatalog.revision

    override suspend fun decide(input: ModelInput): ModelDecision = when (input) {
        is ModelInput.User -> {
            val tools = renderToolDefinitions()
            trace.log("MODEL_TOOL_DEFINITIONS", tools)
            val request = buildDecisionPrompt(input.text, tools)
            trace.log("MODEL_REQUEST", request)
            val output = withContext(Dispatchers.IO) { runner.generate(request) }
            trace.log("MODEL_RAW_OUTPUT", output.generatedText)
            ModelToolCallParser.parseDecision(output.generatedText, allowPlainTextFinal = true)
        }
    }

    override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision {
        // Tool-backed answers are rendered from the observation itself. This prevents a second
        // unconstrained model pass from inventing contact fields absent from the database.
        return GroundedToolResultFormatter.finish(result)
    }

    override fun streamFinal(input: FinalAnswerInput): Flow<String> = flowOf(input.draftText)

    override fun close() = Unit

    private fun renderToolDefinitions(): String =
        config.toolCatalog.contractsByModelName.values.sortedBy { it.modelName }
            .joinToString("\n") { contract ->
                "- ${contract.modelName}: ${contract.description}\n  arguments_schema=${contract.inputSchema}"
            }

    private fun buildDecisionPrompt(userText: String, tools: String): String {
        return """
            ${config.systemInstruction.trim()}

            아래 도구 중 하나가 꼭 필요한 경우에만 JSON 한 개로 응답하세요.
            {"type":"tool_call","name":"tool_name","arguments":{}}
            도구가 필요 없으면 일반 텍스트로만 답하세요. 데이터에 없는 연락처 정보를 만들지 마세요.

            [TOOLS]
            $tools

            [USER]
            $userText
        """.trimIndent()
    }
}
