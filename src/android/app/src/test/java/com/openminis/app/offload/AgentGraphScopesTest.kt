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
    fun cancelling_a_graph_does_not_cancel_the_caller() = runBlocking {
        val scopes = AgentGraphScopes()
        val caller = currentCoroutineContext()[Job]!!
        val inside = CompletableDeferred<Unit>()

        val block = launch {
            scopes.run("task-2b") {
                inside.complete(Unit)
                delay(Long.MAX_VALUE)
            }
        }
        inside.await()

        scopes.cancel("task-2b")
        block.join()

        assertTrue(
            "stopping the graph must not stop the caller's coroutine - in " +
                "the tool path the caller is the whole chat turn",
            caller.isActive,
        )
    }

    @Test
    fun a_late_finally_does_not_remove_a_newer_run_with_the_same_key() = runBlocking {
        val scopes = AgentGraphScopes()
        val firstInside = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()

        // The first run holds the key and waits.
        val first = launch {
            scopes.run("shared-key") {
                firstInside.complete(Unit)
                firstRelease.await()
            }
        }
        firstInside.await()

        // A second run takes the same key. This is the one that must
        // survive: it is the one the user is looking at.
        val secondInside = CompletableDeferred<Unit>()
        val second = launch {
            scopes.run("shared-key") {
                secondInside.complete(Unit)
                delay(Long.MAX_VALUE)
            }
        }
        secondInside.await()

        // The first run ends now. Its finally must not take the second
        // run's registration with it.
        firstRelease.complete(Unit)
        first.join()

        assertTrue(
            "the newer run must still be stoppable after the older run's finally",
            scopes.isCancellable("shared-key"),
        )
        assertTrue(
            "stopping the newer run must report that it stopped",
            scopes.cancel("shared-key"),
        )
        second.join()
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
