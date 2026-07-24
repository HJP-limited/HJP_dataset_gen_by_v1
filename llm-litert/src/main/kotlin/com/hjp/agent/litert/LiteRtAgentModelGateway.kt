package com.hjp.agent.litert

import android.util.Log
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.litert.common.LiteRtNativeAgentModelGateway
import com.hjp.agent.litert.common.LiteRtNativeBackend
import com.hjp.agent.litert.common.LiteRtTraceSink
import java.io.File
import com.hjp.tool.android.MessageDraftGeneration
import com.hjp.tool.android.MessageDraftGenerator
import com.hjp.tool.android.MessageDraftRequest

enum class LiteRtBackendPreference { GPU_THEN_CPU, CPU_ONLY }

/**
 * Android-compatible facade. The native Message.toolCalls/OpenAPI conversion is shared with
 * Desktop in llm-litert-common; Android keeps its existing public type and backend policy.
 */
class LiteRtAgentModelGateway(
    modelId: String = "gemma3-1b-it-int4",
    modelFile: File,
    cacheDirectory: File,
    backendPreference: LiteRtBackendPreference = LiteRtBackendPreference.GPU_THEN_CPU,
    maxNumTokens: Int = 2_048,
) : AgentModelGateway, MessageDraftGenerator {
    private val delegate = LiteRtNativeAgentModelGateway(
        modelId = modelId,
        modelFile = modelFile,
        cacheDirectory = cacheDirectory,
        backend = when (backendPreference) {
            LiteRtBackendPreference.GPU_THEN_CPU -> LiteRtNativeBackend.GPU_THEN_CPU
            LiteRtBackendPreference.CPU_ONLY -> LiteRtNativeBackend.CPU
        },
        maxNumTokens = maxNumTokens,
        strictContactGrounding = false,
        trace = LiteRtTraceSink { stage, message ->
            if (stage == "MODEL_BACKEND_ERROR") Log.w(TAG, message)
        },
    )

    override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
        delegate.openSession(config)

    override fun close() = delegate.close()

    override suspend fun generate(request: MessageDraftRequest): MessageDraftGeneration =
        delegate.generate(request)

    private companion object {
        const val TAG = "HjpLiteRt"
    }
}
