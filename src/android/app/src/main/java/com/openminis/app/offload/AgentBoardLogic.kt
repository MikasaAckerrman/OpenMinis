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
                depsOf(task).all { dep -> byStatus[it] == STATUS_COMPLETED }
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
                val cycle = stack.dropLastWhile { it != stack.last() }
                return cycle.ifEmpty { stack.toList() }
            }
        }
        return null
    }

    /**
     * Cross-turn team header for the spawner: counts + the tail of recent
     * tasks with status. Compact by design — it rides along in a spawn
     * result, and every token there is paid on every subsequent call.
     */
    fun teamSummary(tasks: List<AgentTaskEntity>, tail: Int = 5): String {
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
            sb.appendLine()
                .append("  [${t.status}] ${t.title.take(60)} (${t.roleRequired})$depNote")
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
