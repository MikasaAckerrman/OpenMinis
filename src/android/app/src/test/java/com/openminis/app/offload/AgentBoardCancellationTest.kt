package com.openminis.app.offload

import android.content.Context
import androidx.room.Room
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
 * The recorder reaches the database through the provider singleton, so the
 * test points that singleton at a database of its own. Nothing is left on
 * the user's board, and the test does not depend on the host filesystem.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentBoardCancellationTest {

    private lateinit var context: Context
    private var previousInstance: ProviderDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val database = Room.inMemoryDatabaseBuilder(context, ProviderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        previousInstance = ProviderDatabase.overrideInstanceForTest(database)
    }

    @After
    fun tearDown() {
        ProviderDatabase.overrideInstanceForTest(previousInstance)
    }

    private fun rowOf(taskId: String) =
        runBlocking {
            ProviderDatabase.getInstance(context).agentBoardDao().taskById(taskId)
        }

    @Test
    fun a_terminal_write_survives_the_cancellation_that_caused_it() = runBlocking {
        try {
        val taskId = "task-${UUID.randomUUID()}"
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
        } catch (t: Throwable) {
            println("[AgentBoardCancellationTest] TRACE " + t.stackTraceToString())
            throw t
        }
    }

    @Test
    fun a_late_cancellation_does_not_overwrite_a_finished_task() = runBlocking {
        try {
        val taskId = "task-${UUID.randomUUID()}"
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
        } catch (t: Throwable) {
            println("[AgentBoardCancellationTest] TRACE " + t.stackTraceToString())
            throw t
        }
    }
}
