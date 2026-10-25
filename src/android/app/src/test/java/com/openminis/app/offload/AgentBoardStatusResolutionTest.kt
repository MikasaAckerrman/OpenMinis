package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-agent-panel] The rule that decides the status a board row is shown with.
 *
 * Whatever shows a board row gets its correctness from this rule, and a
 * rule that only exists inside a combine block is a rule nobody can
 * check. It is tested here on its own, with plain strings, so nothing
 * about the device can influence the result.
 *
 * The dangerous direction is the one that must never happen: a task that
 * the board says is finished must never be shown as running again because
 * a snapshot said so. The board is the durable record; a snapshot is what
 * is left in memory.
 */
class AgentRunsStatusResolutionTest {

    @Test
    fun aTerminalStatusInTheDatabaseBeatsTheSnapshot() {
        assertEquals(
            "COMPLETED",
            AgentBoardLogic.resolveAgentTaskStatus(
                dbStatus = "COMPLETED",
                snapshotFinalStatus = "RUNNING",
            ),
        )
    }

    @Test
    fun aTerminalStatusInTheDatabaseBeatsAnotherTerminalStatus() {
        // Both sides say the run ended, but they disagree about how. The
        // database is the record that outlived the process, so it is the
        // one that is believed.
        assertEquals(
            "FAILED",
            AgentBoardLogic.resolveAgentTaskStatus(
                dbStatus = "FAILED",
                snapshotFinalStatus = "COMPLETED",
            ),
        )
    }

    @Test
    fun aTerminalStatusInTheSnapshotBeatsANonTerminalOneInTheDatabase() {
        // The run says it finished and the board did not hear about it: the
        // write was lost. The run's own testimony is newer than the row.
        assertEquals(
            "COMPLETED",
            AgentBoardLogic.resolveAgentTaskStatus(
                dbStatus = "RUNNING",
                snapshotFinalStatus = "COMPLETED",
            ),
        )
    }

    @Test
    fun withoutATerminalStatusAnywhereTheDatabaseStands() {
        assertEquals(
            "RUNNING",
            AgentBoardLogic.resolveAgentTaskStatus(
                dbStatus = "RUNNING",
                snapshotFinalStatus = null,
            ),
        )
        assertEquals(
            "PENDING",
            AgentBoardLogic.resolveAgentTaskStatus(
                dbStatus = "PENDING",
                snapshotFinalStatus = null,
            ),
        )
    }

    @Test
    fun aFinishedTaskIsNeverBroughtBackToLifeByALeftoverSnapshot() {
        // Every terminal status in the database, against a snapshot that
        // claims the run is still going. None of them may change.
        for (terminal in listOf("COMPLETED", "FAILED", "CANCELLED")) {
            assertEquals(
                terminal,
                AgentBoardLogic.resolveAgentTaskStatus(
                    dbStatus = terminal,
                    snapshotFinalStatus = "RUNNING",
                ),
            )
        }
    }

    @Test
    fun aCancelledTaskIsNotTurnedIntoAFinishedOne() {
        // The run was stopped on purpose. A snapshot that says it completed
        // anyway must not rewrite that: the user's decision to stop it is
        // the outcome, not a successful finish.
        assertEquals(
            "CANCELLED",
            AgentBoardLogic.resolveAgentTaskStatus(
                dbStatus = "CANCELLED",
                snapshotFinalStatus = "COMPLETED",
            ),
        )
    }
}
