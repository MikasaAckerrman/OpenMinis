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

    /**
     * [T-board-cancelled-status] A run that was stopped on purpose.
     *
     * Cancelled is not a flavour of failed. The user stopped it, so telling
     * them it failed is a lie about who did what - and the board's own
     * history is the record of that.
     *
     * The column is a plain string, so introducing the value needs no
     * schema migration; the states that matter are the ones the UI has to
     * name.
     */
    const val STATUS_CANCELLED = "CANCELLED"

    /**
     * [T-board-terminal-outcome] How a run ended, decided in one place.
     *
     * The four things a terminal write needs - the status the task moves
     * to, the reason recorded in the ledger, the kind of letter the
     * spawning session receives and the mission event - are one decision,
     * not four independent ifs that can disagree with each other.
     *
     * Cancelled is deliberately not folded into failed. "The user stopped
     * it" and "it broke" are different facts, and the board is the record
     * of who did what.
     */
    data class TerminalOutcome(
        val status: String,
        val reason: String,
        val messageType: String,
        val eventType: String,
    )

    fun terminalOutcome(succeeded: Boolean, cancelled: Boolean): TerminalOutcome = when {
        cancelled -> TerminalOutcome(
            status = STATUS_CANCELLED,
            reason = "cancelled",
            messageType = "cancelled",
            eventType = "RUN_CANCELLED",
        )
        succeeded -> TerminalOutcome(
            status = STATUS_COMPLETED,
            reason = "completed",
            messageType = "result",
            eventType = "RUN_COMPLETED",
        )
        else -> TerminalOutcome(
            status = STATUS_FAILED,
            reason = "failed",
            messageType = "error",
            eventType = "RUN_FAILED",
        )
    }
    const val STATUS_BLOCKED = "BLOCKED"

    /**
     * [T-task-board] A RUNNING row whose updatedAt is older than this is
     * marked stale in summaries: in-memory progress dies with the process,
     * the durable row does not — the marker turns an eternally-busy zombie
     * into an actionable 'treat as failed' signal for the orchestrator.
     */
    const val STALE_AFTER_MS = 60L * 60 * 1000

    /**
     * [T-board-stale-exact] When this process started, in wall-clock millis.
     *
     * A RUNNING row written before this moment belongs to a process that is
     * gone: the run died with it. That is an exact fact, not a guess.
     *
     * [T-board-stale-exact] The older rule - "a RUNNING row untouched for
     * an hour is stale" - cannot tell a dead run from a live one, because
     * updated_at is only written on a status change, on setResult and on
     * insert. It is a BIRTH stamp, not a heartbeat. An agent that has been
     * working for 61 minutes looked dead to it, and the dependency check
     * would then tell the orchestrator to re-spawn an agent that was still
     * running - duplicated work, two agents on the same task.
     *
     * There is no heartbeat on purpose: a periodic write per running task
     * costs more than the question is worth, and the process start is a
     * better answer than any age.
     */
    @Volatile
    var processStartMs: Long = 0L

    /**
     * Record the process start. Call once, at application start.
     */
    fun markProcessStart(nowMs: Long) {
        processStartMs = nowMs
    }

    /**
     * [T-board-stale-exact] Is this task a run that died with its process?
     *
     * [hasLiveProgress] is true when the run is still visible in this
     * process - the caller knows that, the board does not.
     *
     * Two ways to be dead:
     *  - the row was written before this process started, so the process
     *    that owned it is gone;
     *  - the row has been untouched for longer than [STALE_AFTER_MS] and
     *    nothing in this process says the run is still going.
     *
     * A run that is still visible in this process is never stale, however
     * old its row is: it is alive, and the row is merely a birth stamp.
     */
    fun isStale(
        status: String,
        updatedAt: Long,
        nowMs: Long,
        hasLiveProgress: Boolean = false,
    ): Boolean {
        if (status != STATUS_RUNNING) return false
        if (hasLiveProgress) return false
        if (processStartMs > 0L && updatedAt < processStartMs) return true
        return nowMs - updatedAt > STALE_AFTER_MS
    }

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
     * [T-needs-you-lane] Wishlist No.12, concept borrowed from Agent
     * Orchestrator's Kanban: autonomy breaks exactly where agents stall,
     * and a scan-first lane turns "what survived the night" into one look.
     * Includes the intervention states ONLY — FAILED, BLOCKED and
     * RUNNING-older-than-[STALE_AFTER_MS]; healthy work never appears.
     *
     * The task-board tool prints this ABOVE the summary (attention first,
     * chronology second); the compact spawn-time [teamSummary] deliberately
     * does NOT carry it — its tokens are paid on every spawn call.
     *
     * Every line carries the task id (the depends_on handle) and a
     * one-line action: the lane exists to route decisions, not to re-tell
     * the history. BLOCKED is included defensively even though the
     * recorder currently only writes RUNNING→COMPLETED/FAILED — graph-node
     * BLOCKED verdicts may surface as task rows later, and the lane should
     * already know what to do with them.
     */
    fun needsYouSection(
        tasks: List<AgentTaskEntity>,
        maxLines: Int = 8,
        now: Long = System.currentTimeMillis(),
    ): String {
        val severity = { t: AgentTaskEntity ->
            when (t.status) {
                STATUS_FAILED -> 0
                STATUS_BLOCKED -> 1
                else -> 2 // stale RUNNING
            }
        }
        val items = tasks.filter { t ->
            t.status == STATUS_FAILED ||
                t.status == STATUS_BLOCKED ||
                isStale(t.status, t.updatedAt, now)
        }.sortedBy(severity)
        if (items.isEmpty()) return ""
        val overflow = items.size - maxLines
        val sb = StringBuilder()
        sb.append("⚠ Needs you (${items.size}):")
        items.asSequence().take(maxLines).forEach { t ->
            val hint = when (t.status) {
                STATUS_FAILED -> "re-spawn or drop"
                STATUS_BLOCKED -> "read the block reason, unblock"
                else -> "stale >1h — likely died; re-spawn if still needed"
            }
            sb.appendLine()
                .append("  [${t.status}] ${t.id}: ${t.title.take(60)} — $hint")
        }
        if (overflow > 0) {
            sb.appendLine().append("  …and $overflow more (raise the task_board limit)")
        }
        return sb.toString()
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
            val stale = if (isStale(t.status, t.updatedAt, now)) {
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
