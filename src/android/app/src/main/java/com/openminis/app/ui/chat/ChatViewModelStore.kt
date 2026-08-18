package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Process-level cache of ChatViewModels keyed by sessionId. Mirrors iOS
 * `ViewModelCache` — a session's agent loop keeps running even if the user
 * leaves the chat screen, and the row in the sessions list shows a spinning
 * indicator while streaming is in flight.
 *
 * Without this, scoping the ChatViewModel to a NavBackStackEntry means
 * `popBackStack()` would cancel `viewModelScope` and kill the streaming job.
 */
object ChatViewModelStore {

    private const val TAG = "ChatVMStore"

    /**
     * One ViewModelStore per canonical sessionId. Each store contains at most
     * one ChatViewModel (the one created by our factory). When we want to drop
     * a session's VM, we call `clear()` on its store which triggers
     * `onCleared`.
     */
    private val stores = mutableMapOf<String, ViewModelStore>()

    /**
     * [T-android-vm-cache-unbounded] LRU order of canonical session ids, oldest
     * first. Kept separate from [stores] so eviction order survives lookups.
     */
    private val lruOrder = mutableListOf<String>()

    /**
     * [T-session-independence] How many IDLE (no live work) session
     * ViewModels stay resident. Sessions with live work — streaming, a queued
     * prompt awaiting drain, or an in-flight compaction — are ALWAYS resident
     * regardless of this bound (see [pinned] / [evictIfNeeded]); they do not
     * count against it. This is the fix for "3 sessions interfere with each
     * other": the old flat `MAX_RESIDENT = 3` put three actively-working
     * sessions exactly on the eviction boundary, so the next store touch (a
     * draft, the session list, a preview) evicted one of them mid-work —
     * cancelling its scope, dropping its live status, and resetting its
     * compaction counters (the endless-compaction loop). Now only sessions the
     * user merely browsed and left are capped; working sessions never evict
     * one another.
     *
     * Each resident VM holds that session's parsed `agentHistory`, so the idle
     * bound still exists to keep the heap from carrying every chat ever opened.
     * 4 covers the real idle-navigation pattern (current + a couple you came
     * from) on top of however many are actively working.
     */
    private const val MAX_RESIDENT_IDLE = 4

    /**
     * Sessions that must never be evicted regardless of LRU position: a
     * session with LIVE WORK — a streaming agent loop, a queued prompt
     * awaiting drain, or an in-flight compaction — lives in its VM's
     * `viewModelScope`, so clearing that store would cancel unrecoverable
     * work. Registered by the VM whenever [ResidentEvictionPolicy.hasLiveWork]
     * flips (see ChatViewModel's pin observer). "Streaming right now" was too
     * narrow: a session waiting to drain its queue is not streaming this
     * instant, yet evicting it still loses the queued turn.
     */
    private val pinned = mutableSetOf<String>()

    /**
     * Pin/unpin a session against eviction. Called by ChatViewModel whenever
     * its live-work state changes — a cancelled scope mid-work is a lost
     * reply/queue/compaction, strictly worse than holding one extra VM.
     */
    @Synchronized
    fun setPinned(sessionId: String, value: Boolean) {
        val key = resolveKey(sessionId)
        if (value) pinned.add(key) else pinned.remove(key)
        // A newly-pinned session may have been the one about to be evicted;
        // a newly-unpinned one may have freed room to trim the idle tail.
        evictIfNeeded()
    }

    /**
     * Evict least-recently-used IDLE stores beyond [MAX_RESIDENT_IDLE].
     * Protected keys — the active chat plus everything with live work
     * ([pinned]) — are never evicted and never count against the idle bound,
     * so any number of actively-working sessions coexist. Clearing a store
     * cancels its `viewModelScope` and triggers `onCleared`; the next
     * `ownerFor` rebuilds the VM from SQLite, so evicting an IDLE session
     * costs a reload, never data. Decision logic lives in
     * [ResidentEvictionPolicy] (unit-tested).
     */
    @Synchronized
    private fun evictIfNeeded() {
        val active = activeSessionIdInternal?.let { resolveKey(it) }
        val protectedKeys = buildSet {
            addAll(pinned)
            if (active != null) add(active)
        }
        val victims = ResidentEvictionPolicy.keysToEvict(
            lruOrder = lruOrder,
            protectedKeys = protectedKeys,
            maxResidentIdle = MAX_RESIDENT_IDLE,
        )
        for (key in victims) {
            lruOrder.remove(key)
            stores.remove(key)?.let {
                it.clear()
                Log.d(TAG, "evict idle store for $key (resident=${stores.size})")
            }
        }
    }

    /**
     * Draft → canonical mapping. When a draft ("__new__...") session is
     * persisted, we add `draftKey -> realId` here so lookups via the old key
     * (from a ChatScreen whose `sessionId` parameter is still the draft)
     * continue to hit the same live store.
     */
    private val aliases = mutableMapOf<String, String>()

    private fun resolveKey(sessionId: String): String =
        aliases[sessionId] ?: sessionId

    @Synchronized
    fun ownerFor(sessionId: String): ViewModelStoreOwner {
        val key = resolveKey(sessionId)
        val store = stores.getOrPut(key) {
            Log.d(TAG, "allocate store for $key (total=${stores.size + 1})")
            ViewModelStore()
        }
        // Touch: move to the MRU end, then evict whatever fell off the tail.
        lruOrder.remove(key)
        lruOrder.add(key)
        evictIfNeeded()
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

    /**
     * Drop the cached VM for this session (cancels `viewModelScope`, triggers
     * `ChatViewModel.onCleared`). Call when the session is deleted. Also
     * clears any draft alias pointing at this canonical id.
     */
    @Synchronized
    fun release(sessionId: String) {
        val key = resolveKey(sessionId)
        aliases.entries.removeAll { it.value == key }
        lruOrder.remove(key)
        pinned.remove(key)
        stores.remove(key)?.let {
            it.clear()
            Log.d(TAG, "release store for $key (remaining=${stores.size})")
        }
    }

    /**
     * Mark `fromSessionId` (a draft key) as an alias for `toSessionId` (the
     * real, persisted id). The live store stays under the real id; future
     * `ownerFor(draftKey)` lookups resolve to the same store so a ChatScreen
     * still rendering with the draft route continues to see the running VM.
     */
    /**
     * T311: id of the chat the user has on screen right now. Set by
     * `ChatScreen`'s lifecycle hook on enter, cleared on dispose.
     * `minis-config session.*` reads this so reads/writes target the
     * "current session" the same way iOS `AIChatViewModel.activeSessionId`
     * does. `null` = no chat is foregrounded → reads return empty / writes
     * throw `No active session`. Resolves through `aliases` so a draft id
     * still maps to the persisted row.
     */
    @Volatile
    private var activeSessionIdInternal: String? = null

    val activeSessionId: String?
        get() = activeSessionIdInternal?.let { resolveKey(it) }

    @Synchronized
    fun setActiveSession(sessionId: String?) {
        activeSessionIdInternal = sessionId
    }

    @Synchronized
    fun rename(fromSessionId: String, toSessionId: String) {
        if (fromSessionId == toSessionId) return
        val store = stores.remove(fromSessionId)
        if (store != null) {
            stores[toSessionId] = store
        }
        // Carry LRU position + pin across the rename, otherwise a draft that
        // just became a real session looks brand-new to the evictor (or, worse,
        // keeps a stale key pinned forever).
        val lruIdx = lruOrder.indexOf(fromSessionId)
        if (lruIdx >= 0) lruOrder[lruIdx] = toSessionId
        if (pinned.remove(fromSessionId)) pinned.add(toSessionId)
        aliases[fromSessionId] = toSessionId
        Log.d(TAG, "rename store $fromSessionId -> $toSessionId (alias kept)")
    }

    /**
     * One-shot stash for the "Move to…" capsule flow. The source session
     * writes (inputText + attachments) here, navigates to the target,
     * and the target's ChatScreen drains it via [consumePendingTransfer].
     * Mirrors iOS `ViewModelCache.pendingTransfer`. Volatile + simple
     * read/write — only ever touched from the main thread.
     */
    data class PendingTransfer(
        val inputText: String,
        val attachments: List<InputAttachment>,
    )

    @Volatile
    private var pendingTransfer: PendingTransfer? = null

    fun stashPendingTransfer(transfer: PendingTransfer) {
        pendingTransfer = transfer
        Log.d(TAG, "stashPendingTransfer: text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}")
    }

    /** Drain the pending-transfer slot exactly once. */
    fun consumePendingTransfer(): PendingTransfer? {
        val t = pendingTransfer
        pendingTransfer = null
        if (t != null) Log.d(TAG, "consumePendingTransfer: text=${t.inputText.length}ch attachments=${t.attachments.size}")
        return t
    }
}
