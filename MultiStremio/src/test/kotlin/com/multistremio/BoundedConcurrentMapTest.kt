package com.multistremio

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BoundedConcurrentMapTest {
    @Test
    fun requestSlotsAreRefilledWithoutWaitingForASlowBatchSibling() = runBlocking {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val slowFirstRequestActive = AtomicBoolean(false)
        val laterRequestStartedWhileFirstWasSlow = AtomicBoolean(false)

        val result = (0 until 6).toList().boundedConcurrentMap(Semaphore(2)) { index ->
            val current = active.incrementAndGet()
            maximumActive.updateAndGet { previous -> maxOf(previous, current) }
            if (index == 0) slowFirstRequestActive.set(true)
            if (index >= 2 && slowFirstRequestActive.get()) {
                laterRequestStartedWhileFirstWasSlow.set(true)
            }
            try {
                delay(if (index == 0) 160L else 15L)
            } finally {
                if (index == 0) slowFirstRequestActive.set(false)
                active.decrementAndGet()
            }
            index * 2
        }

        assertEquals(listOf(0, 2, 4, 6, 8, 10), result)
        assertTrue("Request concurrency exceeded its configured cap", maximumActive.get() <= 2)
        assertEquals("The configured request slots should be used", 2, maximumActive.get())
        assertTrue(
            "A completed request should release its slot while a slower sibling is still active",
            laterRequestStartedWhileFirstWasSlow.get(),
        )
    }
}
