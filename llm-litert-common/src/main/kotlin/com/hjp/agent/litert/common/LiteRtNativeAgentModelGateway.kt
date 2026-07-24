package com.hjp.agent.litert.common

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.agent.routing.GroundedToolResultFormatter
import com.hjp.agent.routing.ContactGroundingGuard
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.android.MessageDraftGeneration
import com.hjp.tool.android.MessageDraftGenerator
import com.hjp.tool.android.MessageDraftPrompt
import com.hjp.tool.android.MessageDraftRequest
import com.hjp.tool.android.SafeMessageDraftGenerator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

enum class LiteRtNativeBackend { CPU, GPU, NPU, GPU_THEN_CPU }

fun interface LiteRtTraceSink {
    fun log(stage: String, message: String)
}

/**
 * Common LiteRT-LM Kotlin adapter used by Android and the Desktop model-agent mode.
 *
 * Tool execution is deliberately manual: LiteRT-LM only creates [Message.toolCalls];
 * AgentKernel remains the single owner of validation, policy, registry lookup and execution.
 */
class LiteRtNativeAgentModelGateway(
    private val modelId: String,
    private val modelFile: File,
    private val cacheDirectory: File,
    private val backend: LiteRtNativeBackend,
    private val maxNumTokens: Int? = null,
    private val strictContactGrounding: Boolean = false,
    private val trace: LiteRtTraceSink = LiteRtTraceSink { _, _ -> },
) : AgentModelGateway, MessageDraftGenerator {
    private val inferenceQueue = InferenceQueue()
    private val engineResource = SingleEngineResource(::initializeEngine)

    override suspend fun openSession(config: ModelSessionConfig): AgentModelSession {
        require(modelFile.isFile && modelFile.canRead()) {
            "모델 파일을 찾을 수 없습니다: ${modelFile.absolutePath}"
        }
        trace.log(
            "MODEL_CONFIG",
            "model_id=$modelId model_path=${modelFile.absolutePath} model_size_bytes=${modelFile.length()}",
        )
        val toolContracts = config.toolCatalog.contractsByModelName.values.sortedBy { it.modelName }
        trace.log("MODEL_TOOL_DEFINITIONS", toolContracts.joinToString(
            prefix = "[", postfix = "]", separator = ",",
        ) { ContractOpenApiTool(it).getToolDescriptionJsonString() })
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(config.systemInstruction),
            samplerConfig = SamplerConfig(
                topK = config.samplingProfile.topK,
                topP = config.samplingProfile.topP,
                temperature = config.samplingProfile.temperature.toDouble(),
            ),
            tools = toolContracts.map { tool(ContractOpenApiTool(it)) },
            automaticToolCalling = false,
        )
        val conversation = inferenceQueue.run {
            withContext(Dispatchers.Default) {
                engineResource.get().createConversation(conversationConfig)
            }
        }
        return LiteRtNativeAgentModelSession(
            conversation = conversation,
            catalogRevision = config.toolCatalog.revision,
            strictContactGrounding = strictContactGrounding,
            modelId = modelId,
            trace = trace,
            inferenceQueue = inferenceQueue,
        )
    }

    private suspend fun initializeEngine(): Engine {
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "LiteRT-LM cache 디렉터리를 만들 수 없습니다: ${cacheDirectory.absolutePath}"
        }
        var lastError: Throwable? = null
        for (candidateBackend in backendCandidates()) {
            var candidate: Engine? = null
            try {
                candidate = Engine(EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = candidateBackend,
                    maxNumTokens = maxNumTokens,
                    cacheDir = cacheDirectory.absolutePath,
                ))
                val startedAt = System.nanoTime()
                withContext(Dispatchers.Default) { candidate.initialize() }
                trace.log(
                    "MODEL_PERFORMANCE",
                    "model_id=$modelId engine_initialization_ms=${elapsedMillis(startedAt)} backend=$candidateBackend",
                )
                return candidate
            } catch (cancelled: CancellationException) {
                candidate?.close()
                throw cancelled
            } catch (error: Throwable) {
                candidate?.close()
                lastError = error
                trace.log("MODEL_BACKEND_ERROR", "${candidateBackend}: ${error.message}")
            }
        }
        throw IllegalStateException("LiteRT-LM engine initialization failed", lastError)
    }

    private fun backendCandidates(): List<Backend> = when (backend) {
        LiteRtNativeBackend.CPU -> listOf(Backend.CPU())
        LiteRtNativeBackend.GPU -> listOf(Backend.GPU())
        LiteRtNativeBackend.NPU -> listOf(Backend.NPU())
        LiteRtNativeBackend.GPU_THEN_CPU -> listOf(Backend.GPU(), Backend.CPU())
    }

    override suspend fun generate(request: MessageDraftRequest): MessageDraftGeneration {
        require(modelFile.isFile && modelFile.canRead()) {
            "모델 파일을 찾을 수 없습니다: ${modelFile.absolutePath}"
        }
        val conversation = inferenceQueue.run {
            withContext(Dispatchers.Default) {
                engineResource.get().createConversation(ConversationConfig(
                    systemInstruction = Contents.of(
                        "당신은 메시지 초안만 JSON으로 작성합니다. 도구를 호출하거나 설명을 덧붙이지 마세요.",
                    ),
                    automaticToolCalling = false,
                ))
            }
        }
        return try {
            val startedAt = System.nanoTime()
            val raw = inferenceQueue.run {
                withContext(Dispatchers.Default) {
                    conversation.sendMessage(MessageDraftPrompt.build(request)).toString()
                }
            }
            trace.log(
                "MODEL_PERFORMANCE",
                "model_id=$modelId draft_generation_ms=${elapsedMillis(startedAt)}",
            )
            SafeMessageDraftGenerator.fromModelOutput(request, raw, source = modelId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SafeMessageDraftGenerator.fallback(
                request = request,
                source = modelId,
                reason = "model_error:${error::class.java.simpleName}",
            )
        } finally {
            conversation.close()
        }
    }

    override fun close() {
        engineResource.close()
    }
}

private class LiteRtNativeAgentModelSession(
    private val conversation: Conversation,
    override val catalogRevision: String,
    private val strictContactGrounding: Boolean,
    private val modelId: String,
    private val trace: LiteRtTraceSink,
    private val inferenceQueue: InferenceQueue,
) : AgentModelSession {
    private var currentTurnRequiresContactEvidence: Boolean = false
    private var currentTurnRequiresAnyToolEvidence: Boolean = false

    override suspend fun decide(input: ModelInput): ModelDecision = when (input) {
        is ModelInput.User -> inferenceQueue.run {
            withContext(Dispatchers.Default) {
            currentTurnRequiresContactEvidence =
                strictContactGrounding && ContactGroundingGuard.requiresToolEvidence(input.text)
            currentTurnRequiresAnyToolEvidence =
                strictContactGrounding && ContactGroundingGuard.requiresAnyToolEvidence(input.text)
            val context = input.safeCapabilityContext
                ?.takeIf { it.isNotEmpty() }
                ?.let { "\n\n[tool_session_context]\n$it" }
                .orEmpty()
            val request = input.text + context
            trace.log("MODEL_REQUEST", request)
            val startedAt = System.nanoTime()
            conversation.sendMessage(request)
                .also {
                    trace.log(
                        "MODEL_PERFORMANCE",
                        "model_id=$modelId request_latency_ms=${elapsedMillis(startedAt)}",
                    )
                }
                .toDecision(isToolResponse = false, result = null)
            }
        }
    }

    override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision =
        inferenceQueue.run {
            withContext(Dispatchers.Default) {
                val content = Content.ToolResponse(result.modelToolName, result.payload.toKotlinValue())
                trace.log("TOOL_RESPONSE_SENT_TO_MODEL", buildJsonObject {
                    put("name", result.modelToolName)
                    put("response", result.payload)
                }.toString())
                conversation.sendMessage(Message.tool(Contents.of(listOf(content))))
                    .toDecision(isToolResponse = true, result = result)
            }
        }

    override fun streamFinal(input: FinalAnswerInput): Flow<String> = flowOf(input.draftText)

    private fun Message.toDecision(
        isToolResponse: Boolean,
        result: ModelToolResponse?,
    ): ModelDecision {
        val raw = toString()
        trace.log(if (isToolResponse) "MODEL_FINAL_RESPONSE" else "MODEL_RAW_OUTPUT", raw)
        trace.log("MODEL_DECISION_SOURCE", "decision_source=$modelId")
        if (toolCalls.isNotEmpty()) {
            val calls = toolCalls.map { call ->
                val arguments = JsonObject(call.arguments.entries.associate { (key, value) ->
                    key to value.toJsonElement()
                })
                ModelToolCall(UUID.randomUUID().toString(), call.name, arguments)
            }
            return ModelDecision.ToolCalls(calls)
        }
        if (!isToolResponse) {
            trace.log("PARSED_TOOL_CALL", "none")
            trace.log("TOOL_EXECUTION", "not_executed")
            trace.log("TOOL_RESULT", "not_executed")
            trace.log("TOOL_RESPONSE_SENT_TO_MODEL", "not_sent")
            trace.log("MODEL_FINAL_RESPONSE", raw)
        }
        if (strictContactGrounding && result != null) {
            val grounded = GroundedToolResultFormatter.finish(requireNotNull(result))
            trace.log(
                "GROUNDING_VALIDATION",
                "valid=true strategy=strict_grounded_formatter tool=${result.modelToolName} " +
                    "model_text_discarded=true",
            )
            return grounded
        }
        if (!isToolResponse && currentTurnRequiresContactEvidence) {
            trace.log(
                "GROUNDING_VALIDATION",
                "valid=false reason=contact_request_without_contact_tool safe_response=true",
            )
            return ModelDecision.FinalCandidate(ContactGroundingGuard.UNGROUNDED_SAFE_RESPONSE)
        }
        if (!isToolResponse && currentTurnRequiresAnyToolEvidence) {
            trace.log(
                "GROUNDING_VALIDATION",
                "valid=false reason=tool_required_request_without_tool safe_response=true",
            )
            return ModelDecision.FinalCandidate(ContactGroundingGuard.UNEXECUTED_TOOL_SAFE_RESPONSE)
        }
        if (isToolResponse) {
            trace.log("GROUNDING_VALIDATION", "valid=true strategy=model_response_non_contact")
        } else {
            trace.log("GROUNDING_VALIDATION", "valid=true strategy=no_tool_plain_text")
        }
        return if (raw.isNotBlank()) ModelDecision.FinalCandidate(raw)
        else ModelDecision.Invalid("모델이 응답을 생성하지 못했습니다.", retryable = true)
    }

    override fun close() = conversation.close()

}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.mapNotNull { (key, value) ->
        (key as? String)?.let { it to value.toJsonElement() }
    }.toMap())
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

private fun elapsedMillis(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos) / 1_000_000L

private fun JsonElement.toKotlinValue(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> entries.associate { (key, value) -> key to value.toKotlinValue() }
    is JsonArray -> map { it.toKotlinValue() }
    is JsonPrimitive -> booleanOrNull ?: doubleOrNull ?: content
}

private class ContractOpenApiTool(private val contract: ToolContract) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = buildJsonObject {
        put("name", contract.modelName)
        put("description", contract.description)
        put("parameters", contract.inputSchema)
    }.toString()

    override fun execute(paramsJsonString: String): String = buildJsonObject {
        put("ok", false)
        put("error", "manual_tool_execution_required")
    }.toString()
}
