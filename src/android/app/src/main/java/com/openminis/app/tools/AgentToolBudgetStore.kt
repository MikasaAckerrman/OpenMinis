package com.openminis.app.tools

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-session tool-call counter and ceiling for graph workers.
 *
 * Keyed by session id because that is all the tool executor knows about itself
 * (same reasoning as [AgentToolPolicyStore] and [AgentNodeBinding]). One entry
 * per running node; cleared with the rest of the run's per-session state.
 *
 * In-memory only: this counts calls within one node's turn and is meaningless a
 * minute later.
 */
object AgentToolBudgetStore {

    private data class Budget(
        val limit: Int,
        val used: AtomicInteger,
        /** Human role name and owed artifact, so the refusal can be specific. */
        val roleLabel: String,
        val artifact: String,
    )

    private val budgets = ConcurrentHashMap<String, Budget>()

    /**
     * Arm the counter for [sessionId] with [limit] calls.
     *
     * Resets the count: a session is reused across nodes of a sessionGroup, and
     * the next role must start with a full budget rather than inheriting the
     * previous role's spend.
     *
     * [roleLabel] and [artifact] are carried here because the tool executor sees
     * only a session id — without them a refusal could not say WHO ran out or
     * WHAT to deliver instead, which is the whole point of refusing rather than
     * failing.
     */
    fun set(sessionId: String, limit: Int, roleLabel: String = "This agent", artifact: String = "your artifact") {
        if (sessionId.isBlank()) return
        if (limit <= 0) budgets.remove(sessionId)
        else budgets[sessionId] = Budget(limit, AtomicInteger(0), roleLabel, artifact)
    }

    /** Ceiling for [sessionId], or null when unbounded. */
    fun limitFor(sessionId: String): Int? = budgets[sessionId]?.limit

    /** Calls already made, or 0 when unbounded. */
    fun usedBy(sessionId: String): Int = budgets[sessionId]?.used?.get() ?: 0

    fun roleLabelFor(sessionId: String): String = budgets[sessionId]?.roleLabel ?: "This agent"

    fun artifactFor(sessionId: String): String = budgets[sessionId]?.artifact ?: "your artifact"

    /**
     * Count one call and return the new total.
     *
     * Atomic because tool calls within a turn can be dispatched concurrently;
     * a lost increment would silently widen the budget.
     */
    fun recordCall(sessionId: String): Int =
        budgets[sessionId]?.used?.incrementAndGet() ?: 0

    fun clear(sessionId: String) {
        budgets.remove(sessionId)
    }

    fun clearAll() {
        budgets.clear()
    }
}
