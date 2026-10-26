package com.openminis.app.offload

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [T-board-orphan-sweep] The tasks this process is actually running.
 *
 * The board row is not enough to tell: a row in RUNNING says that an
 * executor started, not that one is still alive. The only complete
 * source is the funnel that writes the rows - [AgentBoardRecorder] - so
 * the funnel marks the task live when it writes the row and clears the
 * mark when it writes the terminal status.
 *
 * This is the signal the orphan sweep uses. [AgentRunProgress] is NOT a
 * substitute: it tracks graph runs for the panel, and a subagent row
 * written by the subagent executor never appears there - a sweep built on
 * it would cancel live subagents.
 */
object AgentBoardLiveness {
    private val live = ConcurrentHashMap.newKeySet<String>()

    /**
     * The lock that makes a close and a registration mutually exclusive.
     *
     * A plain lock would not do: the sweep has to hold it across a
     * database write, and that write suspends. A coroutine lock can be
     * held across a suspension, so the sweep can read the live set and
     * close the rows in one go, with no registration able to slip in
     * between the check and the write.
     */
    private val mutex = Mutex()

    /** The executor of this task has started. */
    suspend fun markLive(taskId: String) {
        mutex.withLock { live.add(taskId) }
    }

    /** The executor of this task has ended, successfully or not. */
    suspend fun markDone(taskId: String) {
        mutex.withLock { live.remove(taskId) }
    }

    /**
     * Run the block with the registry locked. Nothing can be marked live
     * or done while it runs.
     */
    suspend fun <T> withExclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** Ids of the tasks this process is running right now. */
    fun liveTaskIds(): Set<String> = live.toSet()

    /**
     * Forget everything.
     *
     * This is a test hook. Production code must never call it: it would
     * mark every running task as dead, and the next sweep would close
     * rows that have a live executor.
     */
    fun clearAll() {
        live.clear()
    }
}
