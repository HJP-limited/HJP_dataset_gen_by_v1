package com.hjp.agent.litert.common

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local lazy resource holder. Concurrent first users share one initialized instance;
 * close is terminal and prevents a second native engine from appearing later in the process.
 */
class SingleEngineResource<T : AutoCloseable>(
    private val factory: suspend () -> T,
) : AutoCloseable {
    private val initializationMutex = Mutex()
    private val stateLock = Any()

    @Volatile
    private var instance: T? = null
    private var closed = false

    suspend fun get(): T {
        instance?.let { return it }
        return initializationMutex.withLock {
            instance?.let { return@withLock it }
            synchronized(stateLock) {
                check(!closed) { "LiteRT-LM engine resource is closed" }
            }
            val created = factory()
            synchronized(stateLock) {
                if (closed) {
                    created.close()
                    error("LiteRT-LM engine resource was closed during initialization")
                }
                instance = created
                created
            }
        }
    }

    override fun close() {
        val current = synchronized(stateLock) {
            if (closed) return
            closed = true
            instance.also { instance = null }
        }
        current?.close()
    }
}

/** Serializes all native inference calls while allowing separate conversations. */
class InferenceQueue {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
