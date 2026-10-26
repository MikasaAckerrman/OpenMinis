package com.openminis.app.offload

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-board-orphan-sweep] The rule that decides whether an active board row
 * has lost its executor.
 *
 * Pure logic on purpose: the board cannot know which runs are alive, the
 * caller passes that in. The cases that matter are the ones that would
 * either kill a live run or keep a dead one alive.
 */
class AgentBoardOrphanRuleTest {

    @After
    fun tearDown() {
        // The process start is a fact about the process, not about the
        // test: without a reset it leaks into the next test.
        AgentBoardLogic.markProcessStart(0L)
    }

    private fun now() = 1_000_000L

    @Test
    fun `a run this process can see is never an orphan`() {
        AgentBoardLogic.markProcessStart(now() - 10_000L)
        val orphan = AgentBoardLogic.isOrphan(
            status = AgentBoardLogic.STATUS_RUNNING,
            updatedAt = now() - 600_000L,
            taskId = "t1",
            liveTaskIds = setOf("t1"),
            nowMs = now(),
        )
        assertFalse("a live run must not be closed", orphan)
    }

    @Test
    fun `a run nobody can see and nobody owns is an orphan`() {
        AgentBoardLogic.markProcessStart(now() - 10_000L)
        val orphan = AgentBoardLogic.isOrphan(
            status = AgentBoardLogic.STATUS_RUNNING,
            updatedAt = now() - 600_000L,
            taskId = "t1",
            liveTaskIds = emptySet(),
            nowMs = now(),
        )
        assertTrue("a run with no executor must be closed", orphan)
    }

    @Test
    fun `a fresh run without a live entry is not an orphan yet`() {
        AgentBoardLogic.markProcessStart(now() - 10_000L)
        val orphan = AgentBoardLogic.isOrphan(
            status = AgentBoardLogic.STATUS_RUNNING,
            updatedAt = now(),
            taskId = "t1",
            liveTaskIds = emptySet(),
            nowMs = now(),
        )
        assertFalse("a run that just started must not be closed", orphan)
    }

    @Test
    fun `a pending row from a dead process is an orphan`() {
        AgentBoardLogic.markProcessStart(now() - 10_000L)
        val orphan = AgentBoardLogic.isOrphan(
            status = AgentBoardLogic.STATUS_PENDING,
            updatedAt = now() - 600_000L,
            taskId = "t1",
            liveTaskIds = emptySet(),
            nowMs = now(),
        )
        assertTrue("a pending row nobody waits for must be closed", orphan)
    }

    @Test
    fun `a pending row of this process waits for an executor`() {
        AgentBoardLogic.markProcessStart(now() - 10_000L)
        val orphan = AgentBoardLogic.isOrphan(
            status = AgentBoardLogic.STATUS_PENDING,
            updatedAt = now(),
            taskId = "t1",
            liveTaskIds = emptySet(),
            nowMs = now(),
        )
        assertFalse("a pending row of this process is not orphaned", orphan)
    }

    @Test
    fun `a blocked row from a dead process is an orphan`() {
        AgentBoardLogic.markProcessStart(now() - 10_000L)
        val orphan = AgentBoardLogic.isOrphan(
            status = AgentBoardLogic.STATUS_BLOCKED,
            updatedAt = now() - 600_000L,
            taskId = "t1",
            liveTaskIds = emptySet(),
            nowMs = now(),
        )
        assertTrue("a blocked row from a dead process must be closed", orphan)
    }

    @Test
    fun `closed rows are never orphans`() {
        AgentBoardLogic.markProcessStart(now() - 10_000L)
        for (status in listOf(
            AgentBoardLogic.STATUS_COMPLETED,
            AgentBoardLogic.STATUS_FAILED,
            AgentBoardLogic.STATUS_CANCELLED,
        )) {
            val orphan = AgentBoardLogic.isOrphan(
                status = status,
                updatedAt = now() - 600_000L,
                taskId = "t1",
                liveTaskIds = emptySet(),
                nowMs = now(),
            )
            assertFalse("a closed row must stay closed, not be touched", orphan)
        }
    }
}
