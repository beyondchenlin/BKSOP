package com.wangzan101.mika.core.action

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceExecutionGateTest {
    @Test(timeout = 10_000) fun independentCallersWaitForTheWholeLease() = runTest {
        val release = CompletableDeferred<Unit>()
        val entered = mutableListOf<String>()
        val owner = launch {
            DeviceExecutionGate.withExclusive {
                entered += "owner"
                release.await()
                entered += "owner done"
            }
        }
        runCurrent()
        val waiter = launch { DeviceExecutionGate.withExclusive { entered += "waiter" } }
        runCurrent()
        try {
            assertEquals(listOf("owner"), entered)
            assertFalse(waiter.isCompleted)
        } finally {
            release.complete(Unit)
        }
        owner.join()
        waiter.join()
        assertEquals(listOf("owner", "owner done", "waiter"), entered)
    }

    @Test(timeout = 10_000) fun actualParallelCallersNeverShareTheDevice() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val completed = AtomicInteger()
        val workers = List(16) {
            async(Dispatchers.Default) {
                start.await()
                repeat(8) {
                    DeviceExecutionGate.withExclusive {
                        val current = active.incrementAndGet()
                        maximum.updateAndGet { previous -> maxOf(previous, current) }
                        try {
                            yield()
                            completed.incrementAndGet()
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }
        }
        start.complete(Unit)
        workers.awaitAll()
        assertEquals(128, completed.get())
        assertEquals(1, maximum.get())
        assertEquals(0, active.get())
    }

    @Test(timeout = 10_000) fun sequentialContextChangesAndActionTimeoutsCanReenter() = runBlocking {
        val visited = mutableListOf<String>()
        DeviceExecutionGate.withExclusive {
            DeviceExecutionGate.withExclusive { visited += "direct" }
            withContext(Dispatchers.Default) {
                DeviceExecutionGate.withExclusive { visited += "dispatcher" }
                withContext(NonCancellable) {
                    withTimeout(1_000) {
                        DeviceExecutionGate.withExclusive { visited += "bounded action" }
                    }
                }
            }
        }
        assertEquals(listOf("direct", "dispatcher", "bounded action"), visited)
    }

    @Test(timeout = 10_000) fun forkedChildrenCannotInheritReentryOrDeadlockTheirParent() = runBlocking {
        val entered = AtomicInteger()
        DeviceExecutionGate.withExclusive {
            val children = List(2) {
                async(Dispatchers.Default) {
                    runCatching { DeviceExecutionGate.withExclusive { entered.incrementAndGet() } }.exceptionOrNull()
                }
            }
            children.awaitAll().forEach { error ->
                assertTrue("child must fail before operating: $error", error is ConcurrentDeviceExecutionException)
            }
            DeviceExecutionGate.withExclusive { entered.incrementAndGet() }
        }
        assertEquals(1, entered.get())
    }

    @Test(timeout = 10_000) fun cancelledWaiterDoesNotAcquireOrLeakTheLease() = runTest {
        val release = CompletableDeferred<Unit>()
        var entered = false
        val owner = launch { DeviceExecutionGate.withExclusive { release.await() } }
        runCurrent()
        val waiter = launch { DeviceExecutionGate.withExclusive { entered = true } }
        runCurrent()
        waiter.cancelAndJoin()
        release.complete(Unit)
        owner.join()
        assertFalse(entered)
        assertEquals("available", DeviceExecutionGate.withExclusive { "available" })
    }

    @Test(timeout = 10_000) fun cancellingTheOwnerReleasesTheLease() = runTest {
        val owner = launch { DeviceExecutionGate.withExclusive { awaitCancellation() } }
        runCurrent()
        owner.cancelAndJoin()
        assertEquals("available", DeviceExecutionGate.withExclusive { "available" })
    }

    @Test(timeout = 10_000) fun exceptionInTheOwnerReleasesTheLease() = runTest {
        val expected = IllegalStateException("action failed")
        val thrown = runCatching { DeviceExecutionGate.withExclusive { throw expected } }.exceptionOrNull()
        // Coroutine stack-trace recovery may copy the exception across a suspension boundary.
        assertEquals(expected.javaClass, thrown?.javaClass)
        assertEquals(expected.message, thrown?.message)
        assertEquals("available", DeviceExecutionGate.withExclusive { "available" })
    }
}
