package com.hjp.agent.routing

import com.example.hjp.LocalToolRoutingModelGateway
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Shared Android/Desktop selection policy: deterministic app-feature routing wins, while the
 * primary model handles other prompts. Primary startup/inference failures keep the router alive.
 */
class RoutingFirstAgentModelGateway(
    private val primary: AgentModelGateway,
    private val toolRouter: AgentModelGateway = LocalToolRoutingModelGateway(),
    private val onPrimaryFailure: (String, Throwable) -> Unit = { _, _ -> },
) : AgentModelGateway {
    override suspend fun openSession(config: ModelSessionConfig): AgentModelSession {
        val routerSession = toolRouter.openSession(config)
        return RoutingFirstAgentModelSession(
            primaryGateway = primary,
            config = config,
            toolRouter = routerSession,
            onPrimaryFailure = onPrimaryFailure,
        )
    }

    override fun close() {
        primary.close()
        toolRouter.close()
    }
}

private class RoutingFirstAgentModelSession(
    private val primaryGateway: AgentModelGateway,
    private val config: ModelSessionConfig,
    private val toolRouter: AgentModelSession,
    private val onPrimaryFailure: (String, Throwable) -> Unit,
) : AgentModelSession {
    override val catalogRevision: String = config.toolCatalog.revision
    private var primarySession: AgentModelSession? = null
    private var primaryOpenAttempted = false
    private var activeSession: AgentModelSession = toolRouter

    override suspend fun decide(input: ModelInput): ModelDecision {
        if (input is ModelInput.User && LocalToolRoutingModelGateway.canRoute(input.text)) {
            activeSession = toolRouter
            return toolRouter.decide(input)
        }
        val model = requirePrimarySession()
        if (model != null) {
            try {
                return model.decide(input).also { activeSession = model }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onPrimaryFailure("primary model decision failed", error)
            }
        }
        activeSession = toolRouter
        return toolRouter.decide(input)
    }

    override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision =
        activeSession.continueWithToolResult(result)

    override fun streamFinal(input: FinalAnswerInput): Flow<String> = activeSession.streamFinal(input)

    override fun close() {
        primarySession?.close()
        primarySession = null
        toolRouter.close()
    }

    private suspend fun requirePrimarySession(): AgentModelSession? {
        primarySession?.let { return it }
        if (primaryOpenAttempted) return null
        primaryOpenAttempted = true
        return try {
            primaryGateway.openSession(config).also { primarySession = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onPrimaryFailure("primary model session unavailable", error)
            null
        }
    }
}
