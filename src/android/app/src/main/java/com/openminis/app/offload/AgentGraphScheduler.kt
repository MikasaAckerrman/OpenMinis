package com.openminis.app.offload

/**
 * [T-agent-graph-scheduler] Pure reference model of AgentGraphRunner's node
 * scheduler, extracted so the traversal order can be proven WITHOUT a build,
 * an emulator, a provider, or the debug channel.
 *
 * Why this exists: the runner's event loop is the one link in the agent
 * pipeline that cannot be checked by reading alone — a subtle ordering bug
 * (reaping completed jobs AFTER the "is work in flight" check) let the entry
 * node finish and then hang the whole run, invisible until an end-to-end
 * device run. Encoding the scheduler's contract here turns that into a unit
 * test: green here means planner -> coder -> reviewer actually sequences.
 *
 * This models ONLY sequencing: given a graph and a function that says how each
 * node's turn ended (COMPLETE / BLOCKED / …), produce the order nodes run in
 * and the terminal status of each. It deliberately omits model calls, prompts,
 * artifacts — those are separate links with their own checks. Keeping it in the
 * production source set (not test) means the real runner can delegate to it
 * later; for now it is the executable spec the runner must not contradict.
 */
object AgentGraphScheduler {

    enum class Outcome { COMPLETE, BLOCKED, NEEDS_CLARIFICATION, FAILED }

    enum class NodeStatus { COMPLETED, BLOCKED, SKIPPED, FAILED }

    data class Result(
        /** Runtime node ids in the order they were dispatched. */
        val dispatchOrder: List<String>,
        val statusByNode: Map<String, NodeStatus>,
        val deadlock: Boolean,
    )

    /**
     * Run the scheduler over [graph]. [outcomeOf] supplies each node's turn
     * result when it executes — this is the stub that stands in for a real
     * model call. A node that [outcomeOf] never gets asked about did not run.
     *
     * Mirrors AgentGraphRunner.executeGraphIn's contract:
     *  - the entry node (and its replicas) start ready;
     *  - a completed node is reaped BEFORE the loop decides work is in flight
     *    (the 13671b4 fix — reaping last would hang here too);
     *  - a successor becomes ready once ALL its predecessors are terminal and
     *    at least one COMPLETED (fan-in);
     *  - a successor with no COMPLETED predecessor is SKIPPED, not run;
     *  - the run ends when every exit node is terminal and nothing is pending.
     */
    fun run(
        graph: AgentGraphSpec,
        outcomeOf: (String) -> Outcome,
    ): Result {
        val dispatchOrder = mutableListOf<String>()
        val status = mutableMapOf<String, NodeStatus>()
        val ready = ArrayDeque<String>()
        val dispatched = mutableSetOf<String>()

        fun runtimeIdsOf(configId: String): List<String> =
            graph.replicaIds[configId] ?: listOf(configId)

        ready.addAll(runtimeIdsOf(graph.entryNodeId))

        fun terminal(id: String) = status[id] != null

        var guard = 0
        while (true) {
            if (guard++ > 10_000) return Result(dispatchOrder, status, deadlock = true)

            // Dispatch everything currently ready (maxParallel is a perf knob,
            // not a correctness one — omitted from the sequencing model).
            while (ready.isNotEmpty()) {
                val id = ready.removeFirst()
                if (id in dispatched) continue
                dispatched.add(id)
                dispatchOrder.add(id)
                status[id] = when (outcomeOf(id)) {
                    Outcome.COMPLETE -> NodeStatus.COMPLETED
                    Outcome.BLOCKED -> NodeStatus.BLOCKED
                    Outcome.NEEDS_CLARIFICATION -> NodeStatus.BLOCKED
                    Outcome.FAILED -> NodeStatus.FAILED
                }
                for (readyId in queueSuccessors(graph, id, status, ::runtimeIdsOf)) {
                    if (readyId !in dispatched) ready.add(readyId)
                }
            }

            val exitRuntimeIds = graph.exitNodeIds.flatMap { runtimeIdsOf(it) }
            val exitsSettled = exitRuntimeIds.all { terminal(it) }
            if (exitsSettled && ready.isEmpty()) break

            if (ready.isEmpty()) {
                // Nothing ready, exits unsettled → stuck. The real runner would
                // be waiting on a job; here there are no jobs, so it is a
                // genuine deadlock in the graph shape.
                return Result(dispatchOrder, status, deadlock = true)
            }
        }
        return Result(dispatchOrder, status, deadlock = false)
    }

    /** Mirror of AgentGraphRunner.queueSuccessors, sequencing-only. */
    private fun queueSuccessors(
        graph: AgentGraphSpec,
        completedRuntimeId: String,
        status: Map<String, NodeStatus>,
        runtimeIdsOf: (String) -> List<String>,
    ): List<String> {
        val statusMut = status as MutableMap<String, NodeStatus>
        val ready = mutableListOf<String>()

        fun terminal(id: String) = statusMut[id] != null

        // A skip must cascade: a node that never runs cannot queue its own
        // successors, so without this a skipped mid-chain node orphans
        // everything downstream and the exit never settles (reported as a
        // deadlock). Worklist of just-settled config ids whose successors need
        // evaluating; a newly-skipped node is added back so its successors are
        // skipped too.
        val settledConfigIds = ArrayDeque<String>()
        settledConfigIds.add(graph.configIdOf(completedRuntimeId))

        while (settledConfigIds.isNotEmpty()) {
            val fromConfig = settledConfigIds.removeFirst()
            for (edge in graph.edges.filter { it.from == fromConfig }) {
                val incoming = graph.edges.filter { it.to == edge.to }
                val allPredRuntimeIds = incoming.flatMap { runtimeIdsOf(it.from) }.distinct()
                if (!allPredRuntimeIds.all { terminal(it) }) continue

                val satisfied = incoming.any { inEdge ->
                    runtimeIdsOf(inEdge.from).any { statusMut[it] == NodeStatus.COMPLETED }
                }
                for (targetRuntimeId in runtimeIdsOf(edge.to)) {
                    if (terminal(targetRuntimeId)) continue
                    if (!satisfied) {
                        statusMut[targetRuntimeId] = NodeStatus.SKIPPED
                    } else {
                        ready.add(targetRuntimeId)
                    }
                }
                // Whether the target became SKIPPED or ready, re-evaluate its
                // successors: a skipped target must propagate the skip; a ready
                // target is handled when it actually runs, but adding it here is
                // harmless because its successors' predecessors are not all
                // terminal yet (it has not run), so they are simply skipped now
                // → guard against that by only cascading the SKIPPED case.
                val targetsSkipped = runtimeIdsOf(edge.to).all {
                    statusMut[it] == NodeStatus.SKIPPED
                }
                if (targetsSkipped) settledConfigIds.add(edge.to)
            }
        }
        return ready
    }

    // ── minimal graph shape for the model (no Android, no serialization) ──

    data class Edge(val from: String, val to: String)

    class AgentGraphSpec(
        val entryNodeId: String,
        val exitNodeIds: List<String>,
        val edges: List<Edge>,
        /** configId -> its runtime ids (replicas). Absent = single, id == configId. */
        val replicaIds: Map<String, List<String>> = emptyMap(),
    ) {
        private val runtimeToConfig: Map<String, String> =
            replicaIds.entries.flatMap { (cfg, rts) -> rts.map { it to cfg } }.toMap()

        fun configIdOf(runtimeId: String): String = runtimeToConfig[runtimeId] ?: runtimeId
    }
}
