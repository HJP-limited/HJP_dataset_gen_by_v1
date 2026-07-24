package com.example.hjp

import android.util.Log
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.routing.RoutingFirstAgentModelGateway

/** Android facade for the shared routing-first policy; it does not own or create an Engine. */
class RoutingFirstAndroidAgentModelGateway(
    primary: AgentModelGateway,
    toolRouter: AgentModelGateway,
) : AgentModelGateway {
    private val delegate = RoutingFirstAgentModelGateway(primary, toolRouter) { message, error ->
        runCatching { Log.w(TAG, message, error) }
    }

    override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
        delegate.openSession(config)

    override fun close() = delegate.close()

    private companion object {
        const val TAG = "HjpRoutingGateway"
    }
}
