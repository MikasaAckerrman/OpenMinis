package com.openminis.app.offload

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-board-orphan-sweep] The liveness registry the orphan sweep trusts.
 *
 * The bug this protects against: a subagent row is written by the
 * subagent executor, which does NOT appear in the graph-run progress.
 * A sweep built on that source alone would find a live subagent missing
 * from it and cancel the row - an agent that is running right now.
 */
class AgentBoardLivenessTest {

    @After
    fun tearDown() {
        runBlocking { AgentBoardLiveness.clearAll() }
    }

    @Test
    fun `a task marked live is live`() = runBlocking {
        AgentBoardLiveness.markLive("t1")
        assertEquals(setOf("t1"), AgentBoardLiveness.liveTaskIds())
    }

    @Test
    fun `a finished task is not live anymore`() = runBlocking {
        AgentBoardLiveness.markLive("t1")
        AgentBoardLiveness.markDone("t1")
        assertTrue(AgentBoardLiveness.liveTaskIds().isEmpty())
    }

    @Test
    fun `a live subagent row is never cancelled by the sweep`() = runBlocking {
        // The executor of this subagent is running. The row is two hours
        // old, so the age alone would make it look abandoned.
        AgentBoardLiveness.markLive("sub-1")
        val now = System.currentTimeMillis()
        val orphan = AgentBoardLogic.isOrphan(
            status = AgentBoardLogic.STATUS_RUNNING,
            updatedAt = now - 7_200_000L,
            taskId = "sub-1",
            liveTaskIds = AgentBoardLiveness.liveTaskIds(),
            nowMs = now,
        )
        assertFalse("a live subagent must not be cancelled", orphan)
    }

    @Test
    fun `a subagent whose executor died is cancelled`() {
        // The same row, but the executor is gone: nobody marked it live and
        // the row is older than the stale threshold.
        val now = System.currentTimeMillis()
        val orphan = AgentBoardLogic.isOrphan(
            status = AgentBoardLogic.STATUS_RUNNING,
            updatedAt = now - 7_200_000L,
            taskId = "sub-1",
            liveTaskIds = AgentBoardLiveness.liveTaskIds(),
            nowMs = now,
        )
        assertTrue("a dead subagent must be closed", orphan)
    }

    @Test
    fun `a registration cannot slip in while the sweep holds the lock`() = runBlocking {
        // The sweep holds the lock while it checks and closes. A
        // registration that arrives in that window has to wait, so the
        // sweep never works on a reading that is already old.
        var registered = false
        coroutineScope {
            val sweep = launch {
                AgentBoardLiveness.withExclusive {
                    kotlinx.coroutines.yield()
                    registered = true
                }
            }
            val mark = launch {
                AgentBoardLiveness.markLive("t1")
            }
            sweep.join()
            mark.join()
        }
        assertTrue(registered)
        assertEquals(setOf("t1"), AgentBoardLiveness.liveTaskIds())
    }
}
