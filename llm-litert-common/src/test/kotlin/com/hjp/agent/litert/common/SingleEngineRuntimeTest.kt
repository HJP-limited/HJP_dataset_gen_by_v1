package com.hjp.agent.litert.common

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SingleEngineRuntimeTest {
    @Test
    fun `concurrent lazy access initializes exactly one engine`() = runBlocking {
        val creations = AtomicInteger()
        val resource = SingleEngineResource {
            delay(20)
            FakeEngine(creations.incrementAndGet())
        }

        val instances = List(20) { async { resource.get() } }.awaitAll()

        assertEquals(1, creations.get())
        instances.forEach { assertSame(instances.first(), it) }
        resource.close()
        assertEquals(1, instances.first().closeCalls.get())
    }

    @Test
    fun `inference queue permits only one native call at a time`() = runBlocking {
        val queue = InferenceQueue()
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        List(12) {
            async {
                queue.run {
                    val now = active.incrementAndGet()
                    maximum.updateAndGet { previous -> maxOf(previous, now) }
                    delay(10)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, maximum.get())
    }
}

private class FakeEngine(val id: Int) : AutoCloseable {
    val closeCalls = AtomicInteger()
    override fun close() {
        closeCalls.incrementAndGet()
    }
}
