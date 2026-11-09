package com.openminis.app.offload

import com.openminis.app.data.model.AgentRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-agent-graph-live-progress] Live, in-process progress of a graph run, so the
 * chat that STARTED the run can show which agent is working right now.
 *
 * Why this exists: a run already narrates itself into a separate showcase
 * session ([AgentRunShowcase]), and the originating chat only got a blank
 * "awaiting response" bubble that sat there for minutes. From the user's seat
 * that is indistinguishable from a hang — the question "why don't I see the
 * agents working?" is a UI gap, not a missing feature in the engine.
 *
 * Deliberately in-memory only: this is the state of a run happening NOW. The
 * durable record is the showcase session plus the trace file, so nothing here
 * needs to survive process death — and persisting it would mean a Room
 * migration for data that is worthless a minute later (same reasoning as
 * AgentToolPolicyStore).
 */
object AgentRunProgress {

    enum class NodeState { RUNNING, COMPLETED, BLOCKED, FAILED, SKIPPED }

    data class Node(
        val runtimeId: String,
        val role: AgentRole,
        val label: String,
        val state: NodeState,
        /** Replica marker like "1/2", or null for a single-instance node. */
        val replicaInfo: String? = null,
        val model: String = "",
        /**
         * Which attempt is in flight, and how many are allowed.
         *
         * Surfaced because a silent retry is the most confusing thing a run can
         * do: on run f0949263 the planner timed out and started over, and from
         * the chat that was three minutes of an unchanging spinner. "attempt 2/3"
         * is the difference between "working" and "stuck".
         */
        val attempt: Int = 1,
        val maxAttempts: Int = 1,
        /** Budget for THIS attempt, so the card can say how long it may wait. */
        val timeoutMs: Long = 0L,
        /**
         * Tool the node is currently running, or null between calls.
         *
         * [A3] The single most informative thing about a long-running node is
         * which tool it is in — "Implementer · shell_execute" tells the user work
         * is happening, where a bare spinner does not.
         */
        val tool: String? = null,
    )

    data class Snapshot(
        val taskId: String,
        val graphName: String,
        val nodes: List<Node> = emptyList(),
        /** Set once the run settles; null while it is still in flight. */
        val finalStatus: String? = null,
    ) {
        val isRunning: Boolean get() = finalStatus == null

        /** The agent currently working, or null between nodes / after the end. */
        val activeNode: Node? get() = nodes.lastOrNull { it.state == NodeState.RUNNING }

        val completedCount: Int get() = nodes.count { it.state == NodeState.COMPLETED }

        /**
         * Progress denominator. The node count is only known as nodes appear —
         * a graph can skip whole branches — so this reports what has been SEEN,
         * never a guess that could show "5 of 3".
         */
        val seenCount: Int get() = nodes.size
    }

    /** taskId -> snapshot. One entry per in-flight run. */
    private val runs = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, Snapshot>> = runs.asStateFlow()

    fun snapshotFor(taskId: String): Snapshot? = runs.value[taskId]

    fun begin(taskId: String, graphName: String) {
        runs.value = runs.value + (taskId to Snapshot(taskId, graphName))
    }

    fun nodeStarted(
        taskId: String,
        runtimeId: String,
        role: AgentRole,
        label: String,
        replicaInfo: String?,
        model: String,
    ) = mutate(taskId) { snap ->
        val node = Node(runtimeId, role, label, NodeState.RUNNING, replicaInfo, model)
        // Replace rather than append when the same runtime id runs again (retry
        // attempts reuse the id) — otherwise a retried node would show twice.
        snap.copy(nodes = snap.nodes.filterNot { it.runtimeId == runtimeId } + node)
    }

    /**
     * A node is starting attempt [attempt] of [maxAttempts], with [timeoutMs] to
     * spend on it.
     *
     * Separate from [nodeStarted] because the first attempt is announced before
     * the timeout is known, and because retries must not reset the row's other
     * fields (model, replica) that were resolved once.
     */
    fun nodeAttempt(
        taskId: String,
        runtimeId: String,
        attempt: Int,
        maxAttempts: Int,
        timeoutMs: Long,
    ) = mutate(taskId) { snap ->
        snap.copy(
            nodes = snap.nodes.map {
                if (it.runtimeId == runtimeId) {
                    it.copy(
                        attempt = attempt,
                        maxAttempts = maxAttempts,
                        timeoutMs = timeoutMs,
                        // A new attempt starts with no tool in flight; leaving
                        // the previous one would claim work that is not running.
                        tool = null,
                    )
                } else {
                    it
                }
            },
        )
    }

    /**
     * [A3] The node is now running [tool], or between calls when null.
     *
     * Updates for a node that is not RUNNING are ignored: a late tool event from
     * a settled node would reanimate a finished row.
     */
    fun nodeTool(taskId: String, runtimeId: String, tool: String?) = mutate(taskId) { snap ->
        snap.copy(
            nodes = snap.nodes.map {
                if (it.runtimeId == runtimeId && it.state == NodeState.RUNNING) {
                    it.copy(tool = tool)
                } else {
                    it
                }
            },
        )
    }

    fun nodeSettled(taskId: String, runtimeId: String, state: NodeState) = mutate(taskId) { snap ->
        snap.copy(
            nodes = snap.nodes.map {
                // Clear the tool along with the state: a settled row must not
                // keep advertising a tool call that is no longer running.
                if (it.runtimeId == runtimeId) it.copy(state = state, tool = null) else it
            },
        )
    }

    /**
     * Record a node that never ran (cascade skip). Skipped nodes matter to the
     * reader: "reviewer SKIPPED" explains an incomplete answer that would
     * otherwise look arbitrary.
     */
    fun nodeSkipped(taskId: String, runtimeId: String, role: AgentRole, label: String) =
        mutate(taskId) { snap ->
            if (snap.nodes.any { it.runtimeId == runtimeId }) {
                nodeSettledIn(snap, runtimeId, NodeState.SKIPPED)
            } else {
                snap.copy(
                    nodes = snap.nodes + Node(runtimeId, role, label, NodeState.SKIPPED),
                )
            }
        }

    fun finish(taskId: String, status: String) = mutate(taskId) { snap ->
        // Any node still marked RUNNING when the run ends never reported a
        // terminal state (crash, cancellation). Leaving it spinning forever in
        // the UI would be a lie, so settle it as FAILED.
        snap.copy(
            nodes = snap.nodes.map {
                if (it.state == NodeState.RUNNING) it.copy(state = NodeState.FAILED, tool = null)
                else it
            },
            finalStatus = status,
        )
    }

    /**
     * [B36-stall-feed] Last time ANY event was filed for the run, wall clock.
     *
     * Deliberately OUTSIDE [runs]: the watchdog polls this several times a
     * second while a batch runs, and a StateFlow write per poll would churn
     * every progress card in the chat. A plain concurrent map is invisible
     * to Compose and costs one put per event.
     */
    private val lastActivityMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * [B45-stall-pulse] Runs whose STREAM has shown at least one pulse.
     * Distinct from [lastActivityMs]: a run files nodeStarted the moment a
     * node begins, so activity exists while the worker is still waiting
     * for its FIRST token — and the batch watchdog must not read that
     * TTFT window as a stall (the vc158 live report: TTFT ran 32-45s
     * under 3-way parallel load and the 30s watchdog killed healthy
     * agents mid-generation). Before the first pulse the hard ceiling
     * owns the wait; the stall detector owns silence AFTER life was
     * proven.
     */
    private val streamPulsed = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * [B45-stall-pulse] True once the run's stream has pulsed at least
     * once. Wired from the worker's stream-event collect (every engine
     * event) and the tool heartbeat (long tools) — see ChatViewModel.
     */
    fun hasStreamLife(taskId: String): Boolean = streamPulsed[taskId] == true

    /**
     * [B36-stall-feed] Record a liveness pulse for [taskId] from OUTSIDE the
     * registry's own event vocabulary. The worker's text stream is the event
     * the registry cannot see: a node writing a long answer files no
     * nodeStarted/nodeTool events, and without this pulse the batch watchdog
     * would read that silence as a stall and kill a healthy agent.
     * Call sites ([B45-stall-pulse] — the B36 design finally wired): the
     * worker's engine-event collect in ChatViewModel (one put per event)
     * and the tool heartbeat's 30s ticker (long tools).
     */
    fun touch(taskId: String) {
        if (!runs.value.containsKey(taskId)) return
        streamPulsed[taskId] = true
        lastActivityMs[taskId] = System.currentTimeMillis()
    }

    /**
     * [B36-stall-feed] Milliseconds since the run's last filed event, or null
     * when nothing has been filed yet (the watchdog treats unknown as alive —
     * a child that has not begun its graph is the hard timeout's problem,
     * not the stall detector's).
     */
    fun idleMs(taskId: String): Long? {
        val last = lastActivityMs[taskId] ?: return null
        return System.currentTimeMillis() - last
    }

    /** Drop a run's state. Called once the chat has committed the final message. */
    fun clear(taskId: String) {
        runs.value = runs.value - taskId
        lastActivityMs.remove(taskId)
        streamPulsed.remove(taskId)
    }

    fun clearAll() {
        runs.value = emptyMap()
        lastActivityMs.clear()
        streamPulsed.clear()
    }

    private fun nodeSettledIn(snap: Snapshot, runtimeId: String, state: NodeState): Snapshot =
        snap.copy(
            nodes = snap.nodes.map {
                if (it.runtimeId == runtimeId) it.copy(state = state) else it
            },
        )

    /**
     * Updates for an unknown taskId are dropped rather than creating a partial
     * run: progress without the graph name would render as a nameless card.
     * Every filed event doubles as a liveness pulse ([B36-stall-feed]) —
     * node/tool transitions are the activity vocabulary the batch watchdog
     * reads between the stream heartbeat's pulses.
     */
    private inline fun mutate(taskId: String, transform: (Snapshot) -> Snapshot) {
        val current = runs.value[taskId] ?: return
        lastActivityMs[taskId] = System.currentTimeMillis()
        runs.value = runs.value + (taskId to transform(current))
    }
}
