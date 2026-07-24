package com.example.hjp

import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.litert.LiteRtAgentModelGateway
import com.hjp.agent.litert.LiteRtBackendPreference
import java.io.File

/**
 * The process owns exactly one LiteRtAgentModelGateway. The same native Engine backs agent
 * decisions and message drafting; only conversations are separated.
 */
object VariantModelGatewayFactory : ModelGatewayFactory {
    override fun create(modelFile: File, cacheDirectory: File): AgentModelGateway {
        val gemma4 = LiteRtAgentModelGateway(
            modelId = BuildConfig.HJP_MODEL_ID,
            modelFile = modelFile,
            cacheDirectory = cacheDirectory,
            backendPreference = LiteRtBackendPreference.CPU_ONLY,
        )
        return RoutingFirstAndroidAgentModelGateway(
            primary = gemma4,
            toolRouter = LocalToolRoutingModelGateway(draftGenerator = gemma4),
        )
    }
}
