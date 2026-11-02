package com.openminis.app.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B20-followup] The dynamic permit gate: the ceiling is re-read on every
 * acquire, DeviceGuard's shrink stops NEW admissions within one poll tick,
 * and permits already held are never revoked.
 */
class DynamicGateTest {

    @Test
    fun `under ceiling tryAcquire passes and releases cleanly`() {
        var ceiling = 2
        val gate = DynamicGate({ ceiling }, pollMs = 1)
        assertTrue(gate.tryAcquire())
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertTrue(gate.tryAcquire())
        gate.release()
        gate.release()
        assertEquals(0, gate.activeCount())
    }

    @Test
    fun `shrinking the ceiling blocks new admissions but keeps holders`() {
        var ceiling = 2
        val gate = DynamicGate({ ceiling }, pollMs = 1)
        assertTrue(gate.tryAcquire())
        assertTrue(gate.tryAcquire())
        // DeviceGuard clamps: the ceiling drops BELOW the active count.
        ceiling = 1
        assertFalse(
            "over-ceiling admission must be refused while holders run",
            gate.tryAcquire(),
        )
        assertEquals("holders are never revoked", 2, gate.activeCount())
        gate.release() // 2 -> 1 active; ceiling=1 so gate is still full
        assertFalse("still full at ceiling=1", gate.tryAcquire())
        gate.release() // 1 -> 0 active; ceiling=1 now has room
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun `withPermit waits for the ceiling to open`() = runTest {
        val ceiling = 1
        val gate = DynamicGate({ ceiling }, pollMs = 1)
        val done = mutableListOf<Int>()
        val jobs = listOf(
            async { gate.withPermit { delay(10); done.add(1) } },
            async { gate.withPermit { done.add(2) } },
        )
        // With ceiling=1, the second waiter completes only after the first releases.
        jobs.forEach { it.join() }
        assertEquals(listOf(1, 2), done)
    }

    @Test
    fun `over-ceiling waiter is cancellable`() = runTest {
        val gate = DynamicGate({ 1 }, pollMs = 1)
        assertTrue(gate.tryAcquire())
        val job = launch { gate.withPermit { } }
        delay(20) // waiter polls past the ceiling at least once
        job.cancel()
        job.join()
        assertEquals(
            "cancelled waiter holds no permit (the test's own one remains)",
            1, gate.activeCount(),
        )
        gate.release()
        assertEquals(0, gate.activeCount())
    }

    @Test
    fun `withPermit returns the block result`() = runTest {
        val gate = DynamicGate({ 2 }, pollMs = 1)
        assertEquals("x", gate.withPermit { "x" })
    }
}
