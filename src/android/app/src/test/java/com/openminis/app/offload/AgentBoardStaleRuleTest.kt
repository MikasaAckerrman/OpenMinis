package com.openminis.app.offload

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-board-stale-exact] The rule that decides whether a running task
 * really died with its process.
 *
 * The rule exists because the obvious version of this question is wrong.
 * updated_at is written when a task changes status, when its result is
 * stored and when the row is inserted - never while the task runs. It is a
 * birth stamp, not a heartbeat, and there is no heartbeat on purpose. Read
 * as a heartbeat, an agent that had been working for an hour looked dead,
 * and the orchestrator would re-spawn an agent that was still running.
 *
 * The exact fact is available without any periodic write: a row written
 * before this process started belongs to a process that is gone.
 */
class AgentBoardStaleRuleTest {

    @After
    fun tearDown() {
        // The process start is a process-wide fact; a test that leaves it
        // behind would change the answer of the next one.
        AgentBoardLogic.markProcessStart(0L)
    }

    @Test
    fun aRowWrittenBeforeThisProcessStartedBelongsToADeadProcess() {
        AgentBoardLogic.markProcessStart(1_000_000L)

        // Written a second before the process started, and only a moment
        // old - age says nothing, the process says it is dead.
        assertTrue(
            AgentBoardLogic.isStale(
                status = AgentBoardLogic.STATUS_RUNNING,
                updatedAt = 999_000L,
                nowMs = 1_010_000L,
            ),
        )
    }

    @Test
    fun aRunStillVisibleInThisProcessIsAliveHoweverOldItsRowIs() {
        AgentBoardLogic.markProcessStart(1_000_000L)

        // The row is two hours old, well past the age threshold, but the run
        // is in front of the user right now. The row is only a birth stamp.
        assertFalse(
            AgentBoardLogic.isStale(
                status = AgentBoardLogic.STATUS_RUNNING,
                updatedAt = 1_000_000L,
                nowMs = 1_000_000L + 2 * 3_600_000L,
                hasLiveProgress = true,
            ),
        )
    }

    @Test
    fun aRowThisProcessWroteAgesOutWhenNothingIsLeftOfIt() {
        AgentBoardLogic.markProcessStart(1_000_000L)

        // Written by this process, but the run is gone and the finishing
        // write was lost. The age rule is the fallback that catches it.
        assertTrue(
            AgentBoardLogic.isStale(
                status = AgentBoardLogic.STATUS_RUNNING,
                updatedAt = 1_000_000L,
                nowMs = 1_000_000L + AgentBoardLogic.STALE_AFTER_MS + 1,
            ),
        )
    }

    @Test
    fun aFreshRowFromThisProcessIsNotStale() {
        AgentBoardLogic.markProcessStart(1_000_000L)

        assertFalse(
            AgentBoardLogic.isStale(
                status = AgentBoardLogic.STATUS_RUNNING,
                updatedAt = 1_000_000L,
                nowMs = 1_000_000L + 1,
            ),
        )
    }

    @Test
    fun aTaskThatHasEndedIsNeverStale() {
        AgentBoardLogic.markProcessStart(1_000_000L)

        for (status in listOf(
            AgentBoardLogic.STATUS_COMPLETED,
            AgentBoardLogic.STATUS_FAILED,
            AgentBoardLogic.STATUS_CANCELLED,
        )) {
            assertFalse(
                "a finished task is not stale: $status",
                AgentBoardLogic.isStale(
                    status = status,
                    updatedAt = 1L,
                    nowMs = 1_000_000L + 10 * 3_600_000L,
                ),
            )
        }
    }
}
