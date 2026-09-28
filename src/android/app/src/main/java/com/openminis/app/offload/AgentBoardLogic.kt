package com.openminis.app.offload

import com.openminis.app.data.db.AgentTaskEntity

/**
 * [T-task-board] The pure decision core of the task board — no DAO, no
 * Context, so every rule is unit-testable on the JVM (the project's test
 * style: AgentWorkerPromptTest pins bytes; this pins decisions).
 *
 * Two rules live here:
 *  1. READINESS over depends_on_task_ids — which PENDING tasks may start
 *     now (all deps COMPLETED). Computed in Kotlin, NOT in SQL: the natural
 *     SQL shape is json_each, and SQLite's JSON1 is only guaranteed from
 *     API 29 while this app's minSdk is 26. A dependency cycle is reported
 *     instead of silently deadlocking the board.
 *  2. TEAM SUMMARY — the cross-turn header the orchestrator sees on its
 *     next spawn: what the team already did, what failed. This is what
 *     makes the board a board rather than a graveyard — the data shapes
 *     the next delegation decision.
 */
object AgentBoardLogic {

    const val STATUS_PENDING = "PENDING"
    const val STATUS_RUNNING = "RUNNING"
    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_BLOCKED = "BLOCKED"

    /**
     * [T-task-board] A RUNNING row whose updatedAt is older than this is
     * marked stale in summaries: in-memory progress dies with the process,
     * the durable row does not — the marker turns an eternally-busy zombie
     * into an actionable 'treat as failed' signal for the orchestrator.
     */
    const val STALE_AFTER_MS = 60L * 60 * 1000

    /**
     * Ids of tasks whose dependencies are all COMPLETED. A task listing a
     * missing dep id is NOT ready (the dep may have been pruned — treat
     * pruned as unsatisfied, the honest reading, and say so in [unmetDeps]).
     * Order: by createdAt ascending — FIFO within a ready wave, stable and
     * matching how the board is displayed.
     */
    fun readyTaskIds(tasks: List<AgentTaskEntity>): List<String> {
        val byStatus = tasks.associate { it.id to it.status }
        return tasks
            .filter { it.status == STATUS_PENDING }
            .sortedBy { it.createdAt }
            .filter { task ->
                depsOf(task).all { dep -> byStatus[dep] == STATUS_COMPLETED }
            }
            .map { it.id }
    }

    /** For each PENDING task, the dep ids that are not COMPLETED (missing or unfinished). */
    fun unmetDeps(tasks: List<AgentTaskEntity>): Map<String, List<String>> {
        val byStatus = tasks.associate { it.id to it.status }
        return tasks
            .filter { it.status == STATUS_PENDING }
            .associate { task ->
                task.id to depsOf(task).filter { byStatus[it] != STATUS_COMPLETED }
            }
            .filterValues { it.isNotEmpty() }
    }

    /** Detects a dependency cycle among PENDING/RUNNING tasks — a board that would deadlock. */
    fun findCycle(tasks: List<AgentTaskEntity>): List<String>? {
        val graph = tasks
            .filter { it.status == STATUS_PENDING || it.status == STATUS_RUNNING }
            .associate { it.id to depsOf(it) }
        val WHITE = 0; val GREY = 1; val BLACK = 2
        val color = graph.keys.associateWithTo(mutableMapOf()) { WHITE }
        val stack = ArrayDeque<String>()

        fun visit(node: String): Boolean {
            color[node] = GREY
            stack.addLast(node)
            for (dep in graph[node].orEmpty()) {
                if (dep !in graph) continue
                when (color[dep]) {
                    GREY -> {
                        stack.addLast(dep)
                        return true
                    }
                    WHITE -> if (visit(dep)) return true
                }
            }
            color[node] = BLACK
            stack.removeLast()
            return false
        }

        for (node in graph.keys) {
            if (color[node] == WHITE && visit(node)) {
                // The grey-stack at the moment of re-entry is the DFS path,
                // cycle included — not necessarily the minimal cycle, but a
                // superset that NAMES every node involved, which is all a
                // warning needs. (A previous dropLastWhile here was a no-op
                // and only obscured that.)
                return stack.toList()
            }
        }
        return null
    }

    /**
     * Cross-turn team header for the spawner: counts + the tail of recent
     * tasks with status. Compact by design — it rides along in a spawn
     * result, and every token there is paid on every subsequent call.
     *
     * Deep-analysis note: the id MUST be in each line — it is the handle
     * depends_on references; a board that shows tasks but not their ids
     * makes the dependency feature unusable end-to-end. RUNNING rows older
     * than [STALE_AFTER_MS] get a staleness marker: the in-memory progress
     * dies with the process, but the durable row lives on — without the
     * marker a crashed run looks eternally busy and blocks its dependents.
     */
    fun teamSummary(
        tasks: List<AgentTaskEntity>,
        tail: Int = 5,
        now: Long = System.currentTimeMillis(),
    ): String {
        if (tasks.isEmpty()) return ""
        val completed = tasks.count { it.status == STATUS_COMPLETED }
        val failed = tasks.count { it.status == STATUS_FAILED }
        val running = tasks.count { it.status == STATUS_RUNNING }
        val pending = tasks.count { it.status == STATUS_PENDING }
        val sb = StringBuilder()
        sb.append("team history: ${tasks.size} task(s) — $completed ok, $failed failed")
        if (running > 0) sb.append(", $running running")
        if (pending > 0) sb.append(", $pending pending")
        tasks.take(tail).forEach { t ->
            val depNote = if (t.dependsOnTaskIds != "[]") " deps:${t.dependsOnTaskIds}" else ""
            val stale = if (t.status == STATUS_RUNNING && now - t.updatedAt > STALE_AFTER_MS) {
                " ⚠ stale (started >1h ago — the run likely died with its process; treat as failed or re-spawn)"
            } else ""
            sb.appendLine()
                .append("  [${t.status}] ${t.id}: ${t.title.take(60)} (${t.roleRequired})$depNote$stale")
        }
        return sb.toString()
    }

    private fun depsOf(task: AgentTaskEntity): List<String> =
        task.dependsOnTaskIds
            .removePrefix("[")
            .removeSuffix("]")
            .split(',')
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
}
