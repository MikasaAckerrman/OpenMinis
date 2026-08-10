package com.openminis.app.offload

import com.openminis.app.data.model.AgentRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNodeTimeoutTest {

    /**
     * The bug this file exists for: on run f0949263 the planner was cut off at
     * the 120 s chat default. Whatever else changes, planning must get more.
     */
    @Test
    fun `planner gets far more than a chat turn`() {
        val base = 120_000L
        val planner = AgentNodeTimeout.timeoutMsFor(AgentRole.ORCHESTRATOR, base)
        assertTrue("planner timeout $planner must exceed the 120s chat default", planner > base)
        assertEquals(360_000L, planner)
    }

    @Test
    fun `implementer gets the most room of all`() {
        val base = 120_000L
        val impl = AgentNodeTimeout.timeoutMsFor(AgentRole.SENIOR_IMPLEMENTER, base)
        val planner = AgentNodeTimeout.timeoutMsFor(AgentRole.ORCHESTRATOR, base)
        assertTrue("implementer $impl should get at least the planner's $planner", impl >= planner)
    }

    @Test
    fun `reviewers stay modest`() {
        val base = 120_000L
        val reviewer = AgentNodeTimeout.timeoutMsFor(AgentRole.CODE_CORRECTNESS_REVIEWER, base)
        val impl = AgentNodeTimeout.timeoutMsFor(AgentRole.SENIOR_IMPLEMENTER, base)
        assertTrue(reviewer < impl)
        assertEquals(180_000L, reviewer)
    }

    @Test
    fun `every role gets at least the floor`() {
        for (role in AgentRole.entries) {
            val t = AgentNodeTimeout.timeoutMsFor(role, 1L)
            assertTrue("$role got $t, below MIN_MS", t >= AgentNodeTimeout.MIN_MS)
        }
    }

    @Test
    fun `no role can exceed the ceiling`() {
        for (role in AgentRole.entries) {
            val t = AgentNodeTimeout.timeoutMsFor(role, 7L * 24 * 3600 * 1000)
            assertTrue("$role got $t, above MAX_MS", t <= AgentNodeTimeout.MAX_MS)
        }
    }

    @Test
    fun `zero base does not disable the engine`() {
        val t = AgentNodeTimeout.timeoutMsFor(AgentRole.ORCHESTRATOR, 0L)
        assertEquals(AgentNodeTimeout.MIN_MS, t)
    }

    @Test
    fun `negative base is treated as zero`() {
        val t = AgentNodeTimeout.timeoutMsFor(AgentRole.ORCHESTRATOR, -5_000L)
        assertEquals(AgentNodeTimeout.MIN_MS, t)
    }

    @Test
    fun `raising the base raises every role`() {
        for (role in AgentRole.entries) {
            val low = AgentNodeTimeout.timeoutMsFor(role, 60_000L)
            val high = AgentNodeTimeout.timeoutMsFor(role, 240_000L)
            assertTrue("$role ignored the base bump", high >= low)
        }
    }

    @Test
    fun `a node that burned the whole budget is not retried`() {
        // This is the f0949263 shape: attempt 1 used the entire timeout, so
        // attempt 2 could only hit the same wall.
        assertFalse(AgentNodeTimeout.shouldRetryAfterTimeout(360_000L, 360_000L))
        assertFalse(AgentNodeTimeout.shouldRetryAfterTimeout(300_000L, 360_000L))
    }

    @Test
    fun `an early failure is still retried`() {
        assertTrue(AgentNodeTimeout.shouldRetryAfterTimeout(5_000L, 360_000L))
        assertTrue(AgentNodeTimeout.shouldRetryAfterTimeout(100_000L, 360_000L))
    }

    @Test
    fun `zero timeout never retries`() {
        assertFalse(AgentNodeTimeout.shouldRetryAfterTimeout(0L, 0L))
    }
}
