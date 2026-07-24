package com.example.hjp

import com.hjp.agent.contract.AgentModelGateway
import java.io.File

/**
 * Implemented separately by the device and emulator source sets so the emulator dependency
 * graph contains no LiteRT-LM Android runtime.
 */
interface ModelGatewayFactory {
    fun create(modelFile: File, cacheDirectory: File): AgentModelGateway
}
