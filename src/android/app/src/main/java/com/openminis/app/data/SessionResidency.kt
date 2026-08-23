package com.openminis.app.data

/**
 * [T-parallel-sessions] Decides which session ViewModels may be evicted from
 * the process-level cache.
 *
 * Why this exists as its own pure module: eviction used to protect only the
 * ONE foreground session plus whatever was streaming at that exact instant.
 * With three sessions in play that is not enough — a session that is waiting
 * for its turn (queued prompt), mid-compaction, or simply the one the user
 * alt-tabbed away from 20 seconds ago is still LIVE work, but it held no
 * protection, so it got cleared. Clearing cancels `viewModelScope`, which:
 *   - kills the session's in-flight/queued work,
 *   - drops its live status so the sessions list renders it as idle while the
 *     chat itself resumes fine once reopened (the "list says dead, chat says
 *     alive" report), and
 *   - resets every in-memory counter, which is what re-armed the compaction
 *     trigger over and over (see [CompactionCooldown]).
 *
 * So residency is not a memory-tuning detail — it is the root of "3 parallel
 * sessions misbehave and appear linked". Sessions are not linked by state;
 * they were linked by competing for 3 cache slots.
 *
 * The rule: a session is EVICTABLE only if it is provably idle. Anything with
 * live work, or anything the user touched recently, stays resident.
 */
object SessionResidency {

    /**
     * How many session ViewModels stay resident when all of them are idle.
     *
     * Raised from 3 to 6: the old value was exactly the number of sessions a
     * user routinely juggles, so the cache lived permanently on its eviction
     * boundary — every incidental owner (a draft, the list, a preview) pushed
     * a real working session out. 6 keeps a realistic working set (three
     * active chats plus drafts/incidentals) resident without unbounding the
     * cache: idle sessions past the limit are still evicted, and eviction
     * still costs only a reload from SQLite, never data.
     */
    const val DEFAULT_MAX_RESIDENT = 6

    /**
     * A session the user left less than this long ago is treated as "recently
     * used" and kept resident even if nothing is running in it. Covers the
     * alt-tab pattern: switch to chat B, ask something, switch back to A.
     * Without a grace window, A is a plain LRU candidate the moment focus
     * moves, so a 3-way rotation evicts one of the three on every switch.
     */
    const val RECENT_USE_GRACE_MS = 10 * 60 * 1000L

    /**
     * Liveness of one cached session, as known by the store. All flags are
     * things the store can observe without reaching into the ViewModel's
     * internals — the VM reports them as they change.
     */
    data class Liveness(
        /** The chat currently on screen. */
        val isForeground: Boolean = false,
        /** A streaming turn is in flight (agent loop running). */
        val isStreaming: Boolean = false,
        /** A compaction/digest pass is in flight. */
        val isCompacting: Boolean = false,
        /** Prompts are queued waiting for the current turn to finish. */
        val hasQueuedWork: Boolean = false,
        /** Wall-clock of the last user interaction with this session. */
        val lastUsedAtMs: Long = 0L,
    ) {
        /**
         * Live work that must never be interrupted. Cancelling the scope here
         * loses a reply, drops a queued prompt, or aborts a compaction
         * half-way — all user-visible damage, none of it recoverable by a
         * reload.
         */
        val hasLiveWork: Boolean
            get() = isStreaming || isCompacting || hasQueuedWork
    }

    /**
     * Whether [liveness] may be evicted right now.
     *
     * Protected: the foreground chat, anything with live work, and anything
     * used within [RECENT_USE_GRACE_MS]. Everything else is fair game.
     */
    fun isEvictable(liveness: Liveness, nowMs: Long): Boolean {
        if (liveness.isForeground) return false
        if (liveness.hasLiveWork) return false
        val age = nowMs - liveness.lastUsedAtMs
        if (liveness.lastUsedAtMs > 0L && age < RECENT_USE_GRACE_MS) return false
        return true
    }

    /**
     * Pick the session ids to evict, given LRU order (oldest first) and each
     * session's liveness.
     *
     * Contract:
     *  - never returns a protected session, even when that means staying over
     *    [maxResident] (holding one extra VM is strictly cheaper than
     *    cancelling live work or resetting a session's compaction state),
     *  - evicts in LRU order so the coldest idle session goes first,
     *  - returns at most as many ids as needed to reach [maxResident].
     */
    fun selectEvictions(
        lruOrder: List<String>,
        liveness: Map<String, Liveness>,
        nowMs: Long,
        maxResident: Int = DEFAULT_MAX_RESIDENT,
    ): List<String> {
        val limit = maxResident.coerceAtLeast(1)
        var resident = lruOrder.size
        if (resident <= limit) return emptyList()
        val victims = mutableListOf<String>()
        for (id in lruOrder) {
            if (resident <= limit) break
            val live = liveness[id] ?: Liveness()
            if (!isEvictable(live, nowMs)) continue
            victims.add(id)
            resident--
        }
        return victims
    }
}
