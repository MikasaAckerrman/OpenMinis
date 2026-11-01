package com.openminis.app.offload

/**
 * [T-agent-dedup] Mechanical duplicate-task gate.
 *
 * The user's rule: parallelism must never mean DUPLICATED work. Two
 * sub-agents doing the same task burn tokens and return contradictory
 * noise — and hoping the MODEL notices the duplication is exactly the
 * "hope instead of mechanics" this codebase forbids. So the executor
 * compares every new task against the team's ACTIVE board rows and the
 * other specs of the same batch, mechanically, before any agent starts.
 *
 * Similarity: Jaccard over normalized content tokens (lowercased words
 * longer than 2 chars). >= 0.70 means the same work: "review the auth
 * module for logic errors" vs "review auth module for logic errors" ≈ 1.0,
 * while "...for security" vs "...for logic" ≈ 0.6 — a distinct focus still
 * passes. Re-delegating FAILED/COMPLETED tasks stays allowed by design:
 * the gate compares ACTIVE board rows only (PENDING/RUNNING/BLOCKED).
 */
object TaskDedup {

    /** Similarity at or above this = the same work (0.0..1.0). */
    const val DUPLICATE_THRESHOLD = 0.70

    fun normalize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length > 2 }
            .toSet()

    /** Jaccard over normalized token sets; empty inputs are never duplicates. */
    fun similarity(a: String, b: String): Double {
        val ta = normalize(a)
        val tb = normalize(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size.toDouble()
    }

    /**
     * The closest duplicate of [candidate] among [activeTasks]
     * (id -> task text), or null when nothing crosses the threshold.
     */
    fun findDuplicate(candidate: String, activeTasks: Map<String, String>): Pair<String, Double>? =
        activeTasks.mapNotNull { (id, text) ->
            val sim = similarity(candidate, text)
            if (sim >= DUPLICATE_THRESHOLD) id to sim else null
        }.maxByOrNull { it.second }
}
