package com.openminis.app.offload

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-graph-cancel] The cancellable scope of a graph run.
 *
 * A graph has to be stoppable on its own, and stopping it must not stop
 * the chat turn that started it.
 *
 * The scope is a child of the caller's job: the caller's cancellation
 * reaches the graph - that is what a child is for - while cancelling the
 * graph does not reach the caller. That difference is the whole point: in
 * the tool path the caller's job is the whole chat turn, and stopping the
 * graph through it would have stopped the model's answer with it.
 *
 * This is a production unit, not a shape that the tests reproduce. The
 * tests drive this class, so a change here has to show up in them.
 */
internal class AgentGraphScopes {

    private val active = ConcurrentHashMap<String, AgentGraphScope>()

    /**
     * Runs [block] in a scope of its own, registered under [taskId].
     *
     * The scope is released on every way out - the block finished, the
     * block threw, the scope was cancelled.
     */
    suspend fun <T> run(taskId: String, block: suspend () -> T): T {
        val scope = AgentGraphScope(taskId, currentCoroutineContext()[Job])
        active[taskId] = scope
        return try {
            withContext(scope.job) { block() }
        } finally {
            // The scope's job has to be finished, not only cancelled.
            //
            // A job that is a child of the caller's keeps the caller from
            // completing until the child is done - that is what a parent
            // waits for. The graph's work is over at this point, but the
            // job was never completed, so the chat turn that started it
            // would have kept waiting for a child that has nothing left to
            // do. Completing it releases the caller.
            //
            // On the cancelled path the job is already finished by the
            // cancellation, and completing a finished job does nothing.
            try {
                scope.job.complete()
            } finally {
                // Only if the registry still holds this very scope: a late
                // finally must never remove the registration of a newer run
                // that happens to carry the same id.
                //
                // The removal is in its own finally, so it does not depend
                // on the completion above: whatever that does, the
                // registration goes, and a run that cannot be stopped
                // leaves nothing behind.
                active.remove(taskId, scope)
            }
        }
    }

    /** True while the run under [taskId] is still going and can be stopped. */
    fun isCancellable(taskId: String): Boolean =
        active[taskId]?.job?.isActive == true

    /**
     * Stops the run under [taskId].
     *
     * Answers false when there is nothing to stop and when the run has
     * already finished: a caller must not be told that a stop worked when
     * it did not.
     */
    fun cancel(taskId: String): Boolean {
        val scope = active[taskId] ?: return false
        if (!scope.job.isActive) return false
        scope.job.cancel()
        return true
    }
}

/** One run's scope. Identity matters: the registry compares by it. */
internal class AgentGraphScope(val taskId: String, parent: Job?) {
    val job: Job = Job(parent)
}
