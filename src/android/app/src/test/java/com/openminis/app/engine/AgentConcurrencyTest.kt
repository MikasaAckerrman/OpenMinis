package com.openminis.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-parallel-agents] The concurrency ceiling has to follow the device and
 * stay in one place, so the scheduler and the executor cannot disagree.
 */
class AgentConcurrencyTest {

    @Test
    fun `more cores mean more parallel agents`() {
        assertEquals(6, AgentConcurrency.parallelCeiling(8))
        assertEquals(5, AgentConcurrency.parallelCeiling(6))
        assertEquals(4, AgentConcurrency.parallelCeiling(4))
    }

    @Test
    fun `a weak device keeps the old ceiling of three`() {
        assertEquals(3, AgentConcurrency.parallelCeiling(2))
        assertEquals(3, AgentConcurrency.parallelCeiling(1))
    }

    @Test
    fun `the ceiling never drops below the old value`() {
        for (cores in 1..16) {
            assertTrue(
                "cores=$cores",
                AgentConcurrency.parallelCeiling(cores) >= 3,
            )
        }
    }

    @Test
    fun `background agents get one step less than the foreground`() {
        assertEquals(5, AgentConcurrency.backgroundCeiling(8))
        assertEquals(2, AgentConcurrency.backgroundCeiling(1))
    }

    @Test
    fun `the device values are in the same range as the rules`() {
        val parallel = AgentConcurrency.parallel
        val background = AgentConcurrency.background
        assertTrue("parallel=$parallel", parallel in 3..6)
        assertTrue("background=$background", background in 2..5)
        assertTrue("background=$background parallel=$parallel", background < parallel)
    }

    // [B48-thermal-steps] The vc162 incident: divider division alone left
    // three workers running at THERMAL_STATUS_MODERATE. The ceilings now
    // step on the raw status — each pin restores the previous status so
    // test-order changes cannot poison the range pins above.
    @Test
    fun `moderate thermal means two workers and single-filed background`() {
        val prev = AgentConcurrency.thermalStatus
        try {
            AgentConcurrency.thermalStatus = 2 // THERMAL_STATUS_MODERATE
            assertEquals(2, AgentConcurrency.parallel)
            assertEquals(1, AgentConcurrency.background)
        } finally {
            AgentConcurrency.thermalStatus = prev
        }
    }

    @Test
    fun `severe thermal means one worker`() {
        val prev = AgentConcurrency.thermalStatus
        try {
            AgentConcurrency.thermalStatus = 4 // THERMAL_STATUS_SEVERE
            assertEquals(1, AgentConcurrency.parallel)
            assertEquals(1, AgentConcurrency.background)
        } finally {
            AgentConcurrency.thermalStatus = prev
        }
    }

    @Test
    fun `io ceiling outruns the agent ceiling on every device`() {
        assertEquals(16, AgentConcurrency.ioParallelCeiling(8))
        assertEquals(8, AgentConcurrency.ioParallelCeiling(4))
        for (cores in 1..16) {
            assertTrue(
                "cores=$cores io=${AgentConcurrency.ioParallelCeiling(cores)} " +
                    "cpu=${AgentConcurrency.parallelCeiling(cores)}",
                AgentConcurrency.ioParallelCeiling(cores) > AgentConcurrency.parallelCeiling(cores),
            )
        }
    }
}
