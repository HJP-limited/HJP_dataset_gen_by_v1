package com.hjp.desktop

import com.example.hjp.LocalToolRoutingModelGateway
import com.example.hjp.ComposeTraceSink
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.agent.core.ToolExecutor
import com.hjp.agent.litert.common.LiteRtTraceSink
import com.hjp.tool.contract.ToolCatalogSnapshot
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class DesktopLogger(
    private val debug: Boolean,
    private val observer: ((String, String) -> Unit)? = null,
) : DesktopTraceSink, LiteRtTraceSink, ComposeTraceSink {
    override fun log(stage: String, message: String) {
        observer?.invoke(stage, message)
        if (debug) println("[$stage] ${message.take(MAX_DEBUG_CHARS)}")
    }

    fun input(text: String) = log("INPUT", text)
    fun final(text: String) = log("FINAL_RESPONSE", text)

    private companion object {
        const val MAX_DEBUG_CHARS = 12_000
    }
}

class TracingAgentModelGateway(
    private val delegate: AgentModelGateway,
    private val logger: DesktopLogger,
    private val mode: RunnerMode,
    private val modelId: String,
) : AgentModelGateway {
    override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
        TracingAgentModelSession(delegate.openSession(config), logger, mode, modelId)

    override fun close() = delegate.close()
}

private class TracingAgentModelSession(
    private val delegate: AgentModelSession,
    private val logger: DesktopLogger,
    private val mode: RunnerMode,
    private val modelId: String,
) : AgentModelSession {
    override val catalogRevision: String get() = delegate.catalogRevision

    override suspend fun decide(input: ModelInput): ModelDecision {
        if (input is ModelInput.User) {
            if (mode == RunnerMode.MODEL_AGENT) {
                logger.log("ROUTING_POLICY", "deterministic_router_bypassed=true")
            } else {
                logger.log("ROUTER", "deterministic_match=${LocalToolRoutingModelGateway.canRoute(input.text)}")
            }
        }
        return delegate.decide(input).also(::report)
    }

    override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision =
        delegate.continueWithToolResult(result).also(::report)

    override fun streamFinal(input: FinalAnswerInput): Flow<String> = delegate.streamFinal(input)

    override fun close() = delegate.close()

    private fun report(decision: ModelDecision) {
        when (decision) {
            is ModelDecision.ToolCalls -> decision.calls.forEach { call ->
                val display = call.display()
                println(if (mode == RunnerMode.MODEL_AGENT) "모델 도구 호출 > $display" else "라우팅 결과 > $display")
                logger.log("PARSED_TOOL_CALL", buildJsonObject {
                    put("source", if (mode == RunnerMode.MODEL_AGENT) modelId else "routing_gateway")
                    put("name", call.modelToolName)
                    put("arguments", call.arguments)
                }.toString())
            }
            is ModelDecision.FinalCandidate -> {
                if (mode != RunnerMode.MODEL_AGENT) {
                    logger.log("ROUTER", "final_candidate")
                }
            }
            is ModelDecision.Invalid -> logger.log("PARSED_TOOL_CALL", "invalid: ${decision.safeReason}")
        }
    }
}

class TracingToolExecutor(
    private val delegate: ToolExecutor,
    private val logger: DesktopLogger,
    private val mockRecorder: DesktopMockActionRecorder,
) : ToolExecutor {
    override suspend fun execute(
        call: ModelToolCall,
        snapshot: ToolCatalogSnapshot,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        logger.log("TOOL_EXECUTION", call.display())
        val result = delegate.execute(call, snapshot, context)
        val raw = result.toJson()
        val mock = mockRecorder.consume()
        logger.log("TOOL_RESULT", if (mock == null) raw.toString() else buildJsonObject {
            put("result", raw)
            put("desktop_mock", mock)
        }.toString())
        println("도구 실행 결과 > ${(mock ?: raw)}")
        return result
    }
}

private fun ModelToolCall.display(): String = "$modelToolName(${arguments.entries.joinToString(", ") { (key, value) -> "$key=$value" }})"

private fun ToolExecutionResult.toJson(): JsonObject = when (this) {
    is ToolExecutionResult.Success -> buildJsonObject {
        put("ok", true)
        put("tool_capability", capabilityId.value)
        put("data", data)
    }
    is ToolExecutionResult.Failure -> buildJsonObject {
        put("ok", false)
        put("tool_capability", capabilityId.value)
        putJsonObject("error") {
            put("code", error.code.value)
            put("message_ko", error.safeMessageKo)
            put("retryable", error.retryable)
        }
    }
}
