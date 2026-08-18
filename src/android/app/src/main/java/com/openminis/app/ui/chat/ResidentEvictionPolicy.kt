package com.openminis.app.ui.chat

/**
 * [T-session-independence] Pure decision core for [ChatViewModelStore]'s LRU
 * eviction. Extracted from the store so the "which sessions may be dropped"
 * rule is unit-testable without Android's ViewModelStore.
 *
 * ## Why this exists
 *
 * The store caches one ChatViewModel per session. A ViewModel owns the
 * session's `viewModelScope`, which is where its streaming job, its queued
 * prompts, and its in-flight compaction live. Calling `clear()` on a store
 * CANCELS that scope — so evicting a session that still has work in flight
 * silently kills that work.
 *
 * The old rule bounded the cache at a flat `MAX_RESIDENT = 3` and protected
 * only two things: the single foreground `activeSessionId`, and sessions that
 * were streaming *at that instant*. A user driving three sessions at once sat
 * exactly on the bound: the moment a fourth owner touched the store (opening
 * the session list, a draft, a preview), the evictor dropped one of the three
 * — cancelling its queued/awaiting work and dropping the streaming status the
 * sessions list observes. That single defect produced every reported symptom:
 * "sessions are linked", "the list says a session isn't working but it works
 * when I open it" (eviction dropped the live status; reopening rebuilds the VM
 * from SQLite), and — because a rebuilt VM resets its in-memory compaction
 * counters — "endless compactions".
 *
 * ## The rule
 *
 * A session with live work (streaming, queued prompts, or compaction in
 * flight) is IRREPLACEABLE while that work runs: rebuilding it from the DB
 * cannot recover a cancelled coroutine. Such sessions are never evicted,
 * regardless of the bound or LRU position. The bound applies only to the
 * *idle* remainder — sessions the user merely browsed and left. So the
 * resident set is `protected ∪ (bounded idle tail)`, and three (or thirty)
 * actively working sessions coexist without ever evicting one another.
 */
object ResidentEvictionPolicy {

    /**
     * Decide which cached session keys to evict.
     *
     * @param lruOrder resident keys, least-recently-used first (eviction scans
     *        from the front so the oldest idle session goes first).
     * @param protectedKeys keys that must never be evicted this pass: the
     *        foreground/active chat plus every session with live work (pinned).
     * @param maxResidentIdle how many IDLE (unprotected) VMs stay resident.
     *        Protected VMs do not count against this bound.
     * @return keys to evict, in eviction order (oldest idle first). Empty when
     *         the idle set is within bounds.
     */
    fun keysToEvict(
        lruOrder: List<String>,
        protectedKeys: Set<String>,
        maxResidentIdle: Int,
    ): List<String> {
        if (maxResidentIdle < 0) return emptyList()
        // Idle candidates, oldest first, excluding anything protected.
        val idle = lruOrder.filter { it !in protectedKeys }
        val overflow = idle.size - maxResidentIdle
        if (overflow <= 0) return emptyList()
        // Drop the oldest `overflow` idle keys; never touch protected keys.
        return idle.take(overflow)
    }

    /**
     * Whether a session currently holds irreplaceable in-memory work and so
     * must be pinned against eviction. Centralised here (rather than inlined
     * as `_isStreaming` at the pin call site) so "live work" has ONE
     * definition: a queued prompt awaiting drain and an in-flight compaction
     * are just as unrecoverable as an active stream — cancelling any of them
     * loses the user's turn.
     */
    fun hasLiveWork(
        isStreaming: Boolean,
        hasQueuedPrompts: Boolean,
        isCompacting: Boolean,
    ): Boolean = isStreaming || hasQueuedPrompts || isCompacting
}
