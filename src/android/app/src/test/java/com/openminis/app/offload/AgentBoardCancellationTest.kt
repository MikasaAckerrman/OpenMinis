package com.openminis.app.offload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * [T-robolectric-room] The terminal write, against a real Room database.
 *
 * A stopped run is not a run that never finished: the record of what
 * happened has to reach the board. The write is made from the catch block
 * of a coroutine that is already cancelled, which is exactly the case that
 * made the record disappear - a cancelled coroutine cannot enter a
 * withContext, so the write silently never happened and the task stayed
 * running for good.
 *
 * The recorder reaches the database through a real context, so the test
 * runs on Robolectric: a real Android runtime and a real Room database,
 * without waiting for a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentBoardCancellationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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

    private fun rowOf(taskId: String) =
        runBlocking {
            ProviderDatabase.getInstance(context).agentBoardDao().taskById(taskId)
        }

    @Test
    fun a_terminal_write_survives_the_cancellation_that_caused_it() = runBlocking {
        val taskId = "task-${UUID.randomUUID()}"
        touchedTaskIds += taskId
        val teamId = "team-${UUID.randomUUID()}"

        AgentBoardRecorder.taskStarted(
            context = context,
            taskId = taskId,
            teamId = teamId,
            roleRequired = "worker",
            title = "the task",
            description = "a task that gets stopped",
            workspaceDir = null,
        )

        // A coroutine that cancels itself, then records the stop. This is
        // what a stopped run does: the cancellation is the reason for the
        // record, and the record is made from inside the cancellation.
        val job = launch(Dispatchers.IO) {
            withContext(Dispatchers.IO) {
                coroutineContext[Job]!!.cancel()
                AgentBoardRecorder.taskFinished(
                    context = context,
                    taskId = taskId,
                    teamId = teamId,
                    assignedAgentId = null,
                    succeeded = false,
                    result = "run cancelled",
                    cancelled = true,
                )
            }
        }
        job.join()

        val row = rowOf(taskId)
        assertNotNull("the task must exist after the write", row)
        assertEquals(
            "a stopped run must be recorded as cancelled, not left running",
            AgentBoardLogic.STATUS_CANCELLED,
            row!!.status,
        )
    }

    @Test
    fun a_late_cancellation_does_not_overwrite_a_finished_task() = runBlocking {
        val taskId = "task-${UUID.randomUUID()}"
        touchedTaskIds += taskId
        val teamId = "team-${UUID.randomUUID()}"

        AgentBoardRecorder.taskStarted(
            context = context,
            taskId = taskId,
            teamId = teamId,
            roleRequired = "worker",
            title = "the task",
            description = "a task that finishes",
            workspaceDir = null,
        )

        AgentBoardRecorder.taskFinished(
            context = context,
            taskId = taskId,
            teamId = teamId,
            assignedAgentId = null,
            succeeded = true,
            result = "the real result",
        )

        // The stop arrives after the work is done. The task already has its
        // outcome, and a stop that comes late must not take it away.
        AgentBoardRecorder.taskFinished(
            context = context,
            taskId = taskId,
            teamId = teamId,
            assignedAgentId = null,
            succeeded = false,
            result = "run cancelled",
            cancelled = true,
        )

        val row = rowOf(taskId)
        assertNotNull("the task must exist after the write", row)
        assertEquals(
            "a finished task must keep its outcome",
            AgentBoardLogic.STATUS_COMPLETED,
            row!!.status,
        )
        assertTrue(
            "the result must be left alone",
            row.resultArtifact?.contains("the real result") == true,
        )
    }
}
