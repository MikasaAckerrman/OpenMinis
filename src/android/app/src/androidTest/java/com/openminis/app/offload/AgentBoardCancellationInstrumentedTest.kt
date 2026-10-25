package com.openminis.app.offload

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.ProviderDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * [T-board-cancelled-status] The terminal write, against a real database.
 *
 * The unit tests around the board use a real SQLite file, but they do not
 * run the recorder: they drive the SQL directly. That leaves the part that
 * actually broke - a terminal write made from a coroutine that is already
 * cancelled - uncovered.
 *
 * This test drives the recorder the way the graph does, and reads the row
 * back through the DAO.
 */
@RunWith(AndroidJUnit4::class)
class AgentBoardCancellationInstrumentedTest {

    private lateinit var context: Context

    private val teamId: String = "team-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    private fun rowOf(taskId: String) =
        runBlocking {
            ProviderDatabase.getInstance(context)
                .agentBoardDao()
                .tasksForTeam(teamId)
                .firstOrNull { it.id == taskId }
        }

    /**
     * The rows this test made. The recorder writes into the app's own
     * database, so the test has to take its rows with it - a test that
     * leaves tasks behind would put ghosts on the user's board.
     */
    private val touchedTaskIds = mutableListOf<String>()

    @After
    fun cleanUp() {
        val db = ProviderDatabase.getInstance(context).openHelper.writableDatabase
        for (id in touchedTaskIds) {
            db.execSQL("DELETE FROM mission_log WHERE task_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM agent_mailbox WHERE task_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM task_status_history WHERE task_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM agent_tasks WHERE id = ?", arrayOf<Any>(id))
        }
    }

    @Test
    fun a_terminal_write_survives_the_cancellation_that_caused_it() = runBlocking {
        val taskId = "task-${UUID.randomUUID()}"
        touchedTaskIds += taskId

        // The task is started normally.
        AgentBoardRecorder.taskStarted(
            context, taskId = taskId, teamId = teamId,
            roleRequired = "tester", title = "t", description = "d",
            workspaceDir = null,
        )
        assertNotNull("the task must exist before it is finished", rowOf(taskId))

        // Now the coroutine is cancelled - and the terminal write is made
        // from inside that cancelled coroutine, which is exactly the
        // situation a stopped run is in.
        val job = launch(Dispatchers.IO) {
            withContext(Dispatchers.IO) {
                // Cancelling ourselves: every suspension point below now
                // fails, and the write has to happen anyway.
                coroutineContext[Job]!!.cancel()
                AgentBoardRecorder.taskFinished(
                    context, taskId = taskId, teamId = teamId,
                    assignedAgentId = null, succeeded = false,
                    result = "run cancelled", cancelled = true,
                )
            }
        }
        job.join()

        val row = rowOf(taskId)
        assertNotNull("the task must still be there", row)
        assertEquals(
            "a stopped run must be recorded as cancelled",
            AgentBoardLogic.STATUS_CANCELLED,
            row!!.status,
        )
    }

    @Test
    fun a_late_cancellation_does_not_overwrite_a_finished_task() = runBlocking {
        val taskId = "task-${UUID.randomUUID()}"
        touchedTaskIds += taskId

        AgentBoardRecorder.taskStarted(
            context, taskId = taskId, teamId = teamId,
            roleRequired = "tester", title = "t", description = "d",
            workspaceDir = null,
        )
        AgentBoardRecorder.taskFinished(
            context, taskId = taskId, teamId = teamId,
            assignedAgentId = null, succeeded = true,
            result = "the real result",
        )

        // A stop arrives after the work is already done.
        AgentBoardRecorder.taskFinished(
            context, taskId = taskId, teamId = teamId,
            assignedAgentId = null, succeeded = false,
            result = "run cancelled", cancelled = true,
        )

        val row = rowOf(taskId)
        assertNotNull("the task must still be there", row)
        assertEquals(
            "a finished task must keep its status",
            AgentBoardLogic.STATUS_COMPLETED,
            row!!.status,
        )
        assertTrue(
            "a finished task must keep its result",
            row.resultArtifact?.contains("the real result") == true,
        )
    }
}
