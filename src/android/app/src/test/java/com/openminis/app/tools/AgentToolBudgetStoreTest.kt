package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-budget-atomic-acquire] tryAcquire must enforce the ceiling atomically:
 * the executor's old check-then-record shape let a parallel batch of N
 * concurrent calls all read `used` before any recorded, admitting N calls
 * into a budget with fewer slots (writers parallelize on distinct paths).
 */
class AgentToolBudgetStoreTest {

    @Test
    fun `acquires up to the limit and refuses beyond`() {
        AgentToolBudgetStore.set("t-single", 3, "Implementer", "the patch")
        assertTrue(AgentToolBudgetStore.tryAcquire("t-single"))
        assertTrue(AgentToolBudgetStore.tryAcquire("t-single"))
        assertTrue(AgentToolBudgetStore.tryAcquire("t-single"))
        assertFalse(AgentToolBudgetStore.tryAcquire("t-single"))
        assertEquals(3, AgentToolBudgetStore.usedBy("t-single"))
        AgentToolBudgetStore.clear("t-single")
    }

    @Test
    fun `refusal consumes nothing`() {
        AgentToolBudgetStore.set("t-nocost", 1, "Implementer", "the patch")
        assertTrue(AgentToolBudgetStore.tryAcquire("t-nocost"))
        val before = AgentToolBudgetStore.usedBy("t-nocost")
        assertFalse(AgentToolBudgetStore.tryAcquire("t-nocost"))
        assertEquals(before, AgentToolBudgetStore.usedBy("t-nocost"))
        AgentToolBudgetStore.clear("t-nocost")
    }

    @Test
    fun `unbounded session always acquires`() {
        // No entry armed → same contract as check(): unlimited.
        assertTrue(AgentToolBudgetStore.tryAcquire("t-unarmed"))
    }

    @Test
    fun `concurrent acquire never overshoots the ceiling`() {
        val budget = 8
        val threads = 24
        AgentToolBudgetStore.set("t-race", budget, "Implementer", "the patch")
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val admitted = AtomicInteger(0)
        val workers = (1..threads).map {
            Thread {
                ready.countDown()
                go.await()
                if (AgentToolBudgetStore.tryAcquire("t-race")) admitted.incrementAndGet()
            }.apply { start() }
        }
        ready.await()
        go.countDown()
        workers.forEach { it.join() }
        // THE invariant: exactly `budget` slots admitted, no overshoot,
        // no lost increments — 24 racers, 8 slots.
        assertEquals(budget, admitted.get())
        assertEquals(budget, AgentToolBudgetStore.usedBy("t-race"))
        AgentToolBudgetStore.clear("t-race")
    }
}
