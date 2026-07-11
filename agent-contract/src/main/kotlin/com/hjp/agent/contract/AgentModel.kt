package com.hjp.agent.contract

import com.hjp.tool.contract.ToolCatalogSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

data class SamplingProfile(
    val temperature: Float = 0.2f,
    val topK: Int = 20,
    val topP: Double = 0.95,
)

data class ModelSessionConfig(
    val systemInstruction: String,
    val toolCatalog: ToolCatalogSnapshot,
    val localeTag: String,
    val samplingProfile: SamplingProfile = SamplingProfile(),
)

sealed interface ModelInput {
    data class User(
        val text: String,
        val safeCapabilityContext: JsonObject? = null,
    ) : ModelInput
}

data class ModelToolCall(
    val callId: String,
    val modelToolName: String,
    val arguments: JsonObject,
)

sealed interface ModelDecision {
    data class ToolCalls(val calls: List<ModelToolCall>) : ModelDecision
    data class FinalCandidate(val draftText: String) : ModelDecision
    data class Invalid(val safeReason: String) : ModelDecision
}

data class ModelToolResponse(
    val callId: String,
    val modelToolName: String,
    val payload: JsonObject,
)

interface AgentModelGateway : AutoCloseable {
    suspend fun openSession(config: ModelSessionConfig): AgentModelSession
    override fun close() = Unit
}

interface AgentModelSession : AutoCloseable {
    val catalogRevision: String
    suspend fun decide(input: ModelInput): ModelDecision
    suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision
    fun streamFinal(draftText: String): Flow<String>
}
