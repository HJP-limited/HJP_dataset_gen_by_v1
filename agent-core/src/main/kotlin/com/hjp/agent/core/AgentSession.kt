package com.hjp.agent.core

import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.SamplingProfile
import com.hjp.tool.contract.SessionStateKey
import com.hjp.tool.contract.SessionStateUpdate
import com.hjp.tool.contract.StoredSessionState
import com.hjp.tool.contract.ToolCatalogSnapshot
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AgentSession(
    val sessionId: String,
    val capabilityState: MutableMap<SessionStateKey, StoredSessionState> = mutableMapOf(),
)

interface AgentSessionStore {
    suspend fun getOrCreate(): AgentSession
    suspend fun update(transform: (AgentSession) -> Unit)
    suspend fun clear()
}

class InMemoryAgentSessionStore : AgentSessionStore {
    private val mutex = Mutex()
    private var session: AgentSession? = null

    override suspend fun getOrCreate(): AgentSession = mutex.withLock {
        session ?: AgentSession(UUID.randomUUID().toString()).also { session = it }
    }

    override suspend fun update(transform: (AgentSession) -> Unit) {
        mutex.withLock { transform(session ?: AgentSession(UUID.randomUUID().toString()).also { session = it }) }
    }

    override suspend fun clear() {
        mutex.withLock { session = null }
    }
}

class AgentSessionManager(
    private val store: AgentSessionStore,
    private val modelGateway: AgentModelGateway,
    private val systemInstruction: String,
    private val localeTag: String,
    private val samplingProfile: SamplingProfile = SamplingProfile(),
) : AutoCloseable {
    private val modelMutex = Mutex()
    private var modelSession: AgentModelSession? = null

    suspend fun getOrCreate(): AgentSession = store.getOrCreate()

    suspend fun requireModelSession(snapshot: ToolCatalogSnapshot): AgentModelSession = modelMutex.withLock {
        val current = modelSession
        if (current != null && current.catalogRevision == snapshot.revision) return@withLock current
        current?.close()
        modelGateway.openSession(ModelSessionConfig(systemInstruction, snapshot, localeTag, samplingProfile))
            .also { modelSession = it }
    }

    suspend fun apply(updates: List<SessionStateUpdate>) {
        if (updates.isEmpty()) return
        store.update { session ->
            updates.forEach { update ->
                val value = update.value
                if (value == null) session.capabilityState.remove(update.key)
                else session.capabilityState[update.key] = StoredSessionState(
                    update.schemaVersion, value, update.expiresAtEpochMillis)
            }
        }
    }

    suspend fun reset() {
        modelMutex.withLock {
            modelSession?.close()
            modelSession = null
            store.clear()
        }
    }

    override fun close() {
        modelSession?.close()
        modelSession = null
        modelGateway.close()
    }
}
