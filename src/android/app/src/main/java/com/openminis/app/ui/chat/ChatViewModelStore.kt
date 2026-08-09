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
     * How many session ViewModels stay resident. Each one holds that session's
     * full `agentHistory` — every message with its `partsJson` already parsed
     * into objects — so the cache was effectively "every chat the user opened
     * since launch, forever": nothing evicted it, `release()` only ran when a
     * session was DELETED. After browsing a few dozen long chats the heap is
     * carrying tens of thousands of parsed messages, which is what turns a
     * later chat open into a GC-bound crawl.
     *
     * 3 covers the real navigation pattern (current chat + the one you came
     * from + one more) while keeping the resident set bounded.
     */
    private const val MAX_RESIDENT = 3

    /**
     * Sessions that must never be evicted regardless of LRU position: a
     * streaming agent loop lives in its VM's `viewModelScope`, so clearing that
     * store mid-stream would cancel the user's in-flight response. Registered by
     * the VM while `isStreaming` is true.
     */
    private val pinned = mutableSetOf<String>()

    /**
     * Pin/unpin a session against eviction. Called by ChatViewModel around a
     * streaming turn — a cancelled scope mid-stream is a lost reply, which is
     * strictly worse than holding one extra VM in memory.
     */
    @Synchronized
    fun setPinned(sessionId: String, value: Boolean) {
        val key = resolveKey(sessionId)
        if (value) pinned.add(key) else pinned.remove(key)
    }

    /**
     * Evict least-recently-used stores beyond [MAX_RESIDENT], skipping the
     * active chat and anything pinned (streaming). Clearing a store cancels its
     * `viewModelScope` and triggers `onCleared`; the next `ownerFor` rebuilds
     * the VM from SQLite, so eviction costs a reload, never data.
     */
    @Synchronized
    private fun evictIfNeeded() {
        val active = activeSessionIdInternal?.let { resolveKey(it) }
        var i = 0
        while (stores.size > MAX_RESIDENT && i < lruOrder.size) {
            val key = lruOrder[i]
            if (key == active || key in pinned) {
                i++
                continue
            }
            lruOrder.removeAt(i)
            stores.remove(key)?.let {
                it.clear()
                Log.d(TAG, "evict store for $key (resident=${stores.size})")
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
