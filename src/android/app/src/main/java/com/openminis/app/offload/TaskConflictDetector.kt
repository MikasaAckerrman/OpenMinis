package com.openminis.app.offload

/**
 * [T-spawn-many] Static conflict check BEFORE parallel dispatch — the cheap
 * half of the orchestrator's "накинуть пачку заведомо неконфликтующих задач".
 *
 * The orchestrator model is told the rule in the tool description, but a
 * prompt is advice and advice drifts. This is the code check: extract
 * file-ish paths from each task text, and if two tasks touch the same path
 * (or a path inside the other's directory), they must NOT run in parallel —
 * two writers on one file is silent data loss, the #1 failure mode of naive
 * parallel agents.
 *
 * Scheduling: greedy round assignment — each task lands in the earliest
 * batch where it conflicts with nobody already there. Batches run
 * sequentially, tasks inside a batch run in parallel.
 *
 * Pure by design (no Android, no IO) so the conflict rules are unit-testable.
 */
object TaskConflictDetector {

    data class Task(
        val index: Int,
        val role: String,
        val text: String,
    )

    data class Plan(
        /** Batches run sequentially; tasks within a batch are conflict-free. */
        val batches: List<List<Task>>,
        /** Human-readable reasons for every forced serialization. */
        val conflictNotes: List<String>,
    ) {
        val isFullyParallel: Boolean get() = batches.size <= 1
    }

    /**
     * Absolute paths (2+ segments) and relative paths containing a slash.
     * A bare filename ("Login.kt") is deliberately NOT matched — too easy to
     * collide with prose.
     */
    private val PATH_REGEX = Regex("""(?:/[\w@.+-]+){2,}|[\w@.+-]+(?:/[\w@.+-]+)+""")

    private val FILE_EXT_REGEX = Regex("""\.[A-Za-z0-9]{1,6}$""")

    /**
     * Roots every task mentions for free (the shared workspace, temp dirs).
     * A shared MENTION is not a shared WRITE: without this list two tasks
     * that both name the workspace would serialize for no reason.
     */
    private val IGNORED_EXACT = setOf(
        "/var/minis", "/var/minis/workspace", "/var/minis/shared",
        "/var/minis/attachments", "/var/minis/offloads", "/var/minis/memory",
        "/var/minis/skills", "/tmp", "/data/data", "/var",
    )

    fun extractPaths(text: String): Set<String> =
        PATH_REGEX.findAll(text)
            .map { it.value.trim('.', ',', ';', ')', ']', '"', '\'').lowercase() }
            .filter { token ->
                // Must look like a file (extension) or a real directory (2+
                // segments, not a generic root).
                val hasExt = FILE_EXT_REGEX.containsMatchIn(token.substringAfterLast('/'))
                val generic = token in IGNORED_EXACT
                (hasExt || token.count { it == '/' } >= 2) && !generic
            }
            .toSet()

    /** Conflict when paths are equal or one sits inside the other's directory. */
    private fun overlapping(a: Set<String>, b: Set<String>): Set<String> {
        val shared = mutableSetOf<String>()
        for (x in a) {
            for (y in b) {
                if (x == y || x.startsWith("$y/") || y.startsWith("$x/")) {
                    shared.add(if (x.length >= y.length) x else y)
                }
            }
        }
        return shared
    }

    fun plan(tasks: List<Task>): Plan {
        if (tasks.isEmpty()) return Plan(emptyList(), emptyList())
        val paths = tasks.associate { it.index to extractPaths(it.text) }
        val batches = mutableListOf<MutableList<Task>>()
        val notes = mutableListOf<String>()

        for (task in tasks) {
            val taskPaths = paths[task.index].orEmpty()
            var placed = false
            for (batch in batches) {
                val clash = batch.firstOrNull { overlapping(paths[it.index].orEmpty(), taskPaths).isNotEmpty() }
                if (clash == null) {
                    batch.add(task)
                    placed = true
                    break
                }
            }
            if (!placed) {
                val clashWith = batches.flatten()
                    .map { it to overlapping(paths[it.index].orEmpty(), taskPaths) }
                    .firstOrNull { it.second.isNotEmpty() }
                batches.add(mutableListOf(task))
                if (clashWith != null) {
                    notes.add(
                        "task #${task.index + 1} (${task.role}) runs AFTER task #${clashWith.first.index + 1} " +
                            "— both touch ${clashWith.second.joinToString(", ")}",
                    )
                }
            }
        }
        return Plan(batches, notes)
    }
}
