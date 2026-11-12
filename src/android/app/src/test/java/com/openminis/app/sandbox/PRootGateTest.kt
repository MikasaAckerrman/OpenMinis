package com.openminis.app.sandbox

import com.openminis.app.engine.AgentConcurrency
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B51-proot-gate] The ptrace-storm bound: worker shells may never exceed
 * the ceiling, and the ceiling itself steps with thermal (2 on a healthy
 * chip, 1 under SEVERE) — a DynamicGate re-reads it on every acquire
 * attempt, so a thermal escalation clamps NEW admissions without ever
 * cancelling a running pair. main/vault bypass the gate by design: the
 * user's own session must never wait behind background workers.
 */
class PRootGateTest {

    private suspend fun withThermal(status: Int, block: suspend () -> Unit) {
        val saved = AgentConcurrency.thermalStatus
        AgentConcurrency.thermalStatus = status
        try {
            block()
        } finally {
            AgentConcurrency.thermalStatus = saved
        }
    }

    @Test
    fun `ceiling is two on a healthy chip and under moderate throttle`() {
        assertEquals(2, PRootGate.ceilingFor(0))
        assertEquals(2, PRootGate.ceilingFor(2))
    }

    @Test
    fun `severe throttle clamps the ceiling to one`() {
        assertEquals(1, PRootGate.ceilingFor(4))
        assertEquals(1, PRootGate.ceilingFor(5))
    }

    @Test
    fun `a third worker waits for a slot - the storm bound`() = runBlocking {
        withThermal(0) {
            assertEquals(0, PRootGate.activeCount())
            PRootGate.beginWorkerSlot()
            PRootGate.beginWorkerSlot()
            assertEquals(2, PRootGate.activeCount())
            // Ceiling 2: the third begin must SUSPEND, not admit. A bounded
            // probe proves it (returns null on timeout instead of hanging).
            assertNull(
                "a third worker must wait for a slot at ceiling 2",
                withTimeoutOrNull(200) { PRootGate.beginWorkerSlot() },
            )
            assertEquals(2, PRootGate.activeCount())
            // Releasing one slot lets the waiter through.
            PRootGate.endWorkerSlot()
            val admitted = withTimeoutOrNull(1_000) { PRootGate.beginWorkerSlot() }
            assertTrue("a freed slot must admit the waiter", admitted != null)
            assertEquals(2, PRootGate.activeCount())
            PRootGate.endWorkerSlot()
            PRootGate.endWorkerSlot()
            assertEquals(0, PRootGate.activeCount())
        }
    }

    @Test
    fun `a lone worker runs immediately even under severe`() = runBlocking {
        withThermal(4) {
            val admitted = withTimeoutOrNull(1_000) { PRootGate.beginWorkerSlot() }
            assertTrue("ceiling 1 admits its first slot", admitted != null)
            // And the second one waits:
            assertNull(
                withTimeoutOrNull(200) { PRootGate.beginWorkerSlot() },
            )
            PRootGate.endWorkerSlot()
            assertEquals(0, PRootGate.activeCount())
        }
    }

    @Test
    fun `thermal escalation clamps new admissions without touching holders`() = runBlocking {
        withThermal(0) {
            PRootGate.beginWorkerSlot()
            PRootGate.beginWorkerSlot()
            assertEquals(2, PRootGate.activeCount())
            // Escalate to SEVERE while both slots are held: the holders are
            // never cancelled (the gate's invariant), but the ceiling drops —
            // release one, and the next admission sees ceiling 1.
            AgentConcurrency.thermalStatus = 4
            PRootGate.endWorkerSlot()
            assertEquals(1, PRootGate.activeCount())
            assertNull(
                "under severe the freed slot must stay above the new ceiling",
                withTimeoutOrNull(200) { PRootGate.beginWorkerSlot() },
            )
            assertEquals(1, PRootGate.activeCount())
            PRootGate.endWorkerSlot()
            assertEquals(0, PRootGate.activeCount())
        }
    }
}
