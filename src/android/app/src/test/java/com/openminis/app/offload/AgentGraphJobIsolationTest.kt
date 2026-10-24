package com.openminis.app.offload

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-graph-cancel] The job a graph runs in.
 *
 * A graph has to be stoppable on its own, and stopping it must not stop
 * the chat turn that started it. The shape below is the one the executor
 * builds, and every test states one property that shape has to have.
 *
 * The shape matters more than it looks. The child job is a child of the
 * caller's, so the caller's cancellation reaches the graph - that is what
 * a child is for. The other direction must not exist: in the tool path
 * the caller's job is the whole chat turn, and stopping the graph that
 * way would have stopped the model's answer with it.
 */
class AgentGraphJobIsolationTest {

    /** A child of the caller's, exactly as the executor builds it. */
    private fun graphJobOf(parent: Job?): Job = Job(parent)

    @Test
    fun cancelling_the_graph_does_not_cancel_the_caller() = runBlocking {
        val caller = currentCoroutineContext()[Job]!!
        val graph = graphJobOf(caller)

        val inside = CompletableDeferred<Unit>()
        val block = launch {
            withContext(graph) {
                inside.complete(Unit)
                delay(Long.MAX_VALUE) // never returns on its own
            }
        }
        inside.await()

        graph.cancel()
        block.join()

        assertTrue(
            "the caller's job must survive the graph's cancellation",
            caller.isActive,
        )
    }

    @Test
    fun cancelling_the_caller_cancels_the_graph() = runBlocking {
        val caller = currentCoroutineContext()[Job]!!
        val graph = graphJobOf(caller)

        val inside = CompletableDeferred<Unit>()
        val block = launch {
            withContext(graph) {
                inside.complete(Unit)
                delay(Long.MAX_VALUE)
            }
        }
        inside.await()

        caller.cancel()
        block.join()

        assertTrue(
            "the graph must not outlive the caller that started it",
            graph.isCancelled,
        )
    }

    @Test
    fun a_graph_that_finished_lets_the_caller_finish() = runBlocking {
        val caller = currentCoroutineContext()[Job]!!
        val graph = graphJobOf(caller)

        val block = launch {
            withContext(graph) {
                delay(1) // the graph's work
            }
        }
        block.join()

        // The executor completes the graph's job when the run is over. If
        // it did not, the caller would keep waiting for a child that never
        // finishes - and the chat turn would never end.
        graph.complete()

        withTimeout(5_000) {
            caller.join()
        }
        assertTrue("the caller must be able to finish", caller.isCompleted)
    }

    @Test
    fun a_graph_job_that_is_never_completed_keeps_the_caller_waiting() = runBlocking {
        val caller = currentCoroutineContext()[Job]!!
        val graph = graphJobOf(caller)

        // The other half of the test above: a child that is never
        // completed keeps its parent from completing. This is why the
        // executor completes the graph's job and not only cancels it.
        val finished = CompletableDeferred<Boolean>()
        val watcher = launch {
            caller.join()
            finished.complete(true)
        }

        val completedInTime = runCatching {
            withTimeout(300) { finished.await() }
        }.isSuccess
        watcher.cancel()

        assertFalse(
            "an unfinished child must keep the parent waiting - otherwise " +
                "this test proves nothing about the one above",
            completedInTime,
        )
    }
}
