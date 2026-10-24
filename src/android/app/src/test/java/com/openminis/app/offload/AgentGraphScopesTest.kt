package com.openminis.app.offload

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-graph-cancel] The scope a graph runs in.
 *
 * These tests drive the production class, not a shape copied out of it.
 * A copy would keep passing after the production code changed, which is
 * exactly what a test must not do.
 */
class AgentGraphScopesTest {

    @Test
    fun a_finished_run_returns_its_result_and_frees_the_caller() = runBlocking {
        val scopes = AgentGraphScopes()
        val caller = currentCoroutineContext()[Job]!!

        val result = scopes.run("task-1") { "done" }

        assertEquals("the run must return what the block returned", "done", result)
        withTimeout(5_000) { caller.join() }
        assertTrue("the caller must be able to finish", caller.isCompleted)
    }

    @Test
    fun a_cancelled_run_propagates_the_cancellation_and_releases_the_registration() =
        runBlocking {
            val scopes = AgentGraphScopes()
            val inside = CompletableDeferred<Unit>()

            val block = launch {
                scopes.run("task-2") {
                    inside.complete(Unit)
                    delay(Long.MAX_VALUE)
                }
            }
            inside.await()

            assertTrue("a running graph must be stoppable", scopes.isCancellable("task-2"))
            assertTrue("the stop must be reported as done", scopes.cancel("task-2"))

            block.join()
            assertTrue(
                "the cancellation must reach the caller of the scope",
                block.isCancelled,
            )
            assertFalse(
                "a cancelled run must not stay stoppable",
                scopes.isCancellable("task-2"),
            )
        }

    @Test
    fun stopping_a_run_that_already_finished_answers_false() = runBlocking {
        val scopes = AgentGraphScopes()
        scopes.run("task-3") { "done" }

        assertFalse(
            "a finished run must not be reported as stopped",
            scopes.cancel("task-3"),
        )
        assertFalse(
            "a finished run must not stay stoppable",
            scopes.isCancellable("task-3"),
        )
    }

    @Test
    fun cancelling_the_caller_cancels_the_graph() = runBlocking {
        val scopes = AgentGraphScopes()
        val caller = currentCoroutineContext()[Job]!!
        val inside = CompletableDeferred<Unit>()

        val block = launch {
            scopes.run("task-4") {
                inside.complete(Unit)
                delay(Long.MAX_VALUE)
            }
        }
        inside.await()

        caller.cancel()
        block.join()

        assertFalse(
            "the graph must not outlive the caller that started it",
            scopes.isCancellable("task-4"),
        )
    }

    @Test
    fun an_unfinished_child_keeps_its_parent_waiting() = runBlocking {
        // The premise of the first test. Without it, that test would pass
        // for the wrong reason: if an unfinished child did not keep the
        // parent waiting, releasing the caller would be no achievement.
        val parent = Job()
        val child = Job(parent)

        val finished = CompletableDeferred<Boolean>()
        val watcher = launch {
            parent.join()
            finished.complete(true)
        }

        val completedInTime = runCatching {
            withTimeout(300) { finished.await() }
        }.isSuccess
        watcher.cancel()

        assertFalse(
            "an unfinished child must keep the parent waiting",
            completedInTime,
        )
    }
}
