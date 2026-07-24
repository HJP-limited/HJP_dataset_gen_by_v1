package com.example.hjp

import com.hjp.agent.contract.AgentModelGateway
import java.io.File

/** Model-free implementation. No LiteRT-LM type is referenced from this source set. */
object VariantModelGatewayFactory : ModelGatewayFactory {
    override fun create(modelFile: File, cacheDirectory: File): AgentModelGateway =
        LocalToolRoutingModelGateway()
}
