package com.openminis.app.data

import android.content.Context

/**
 * [crash-safe-draft] Durable, per-session composer draft + last-sent backup.
 *
 * ## Why this exists
 *
 * Two ways a user loses text they typed:
 *   1. They type a long prompt, navigate away / the app is killed, come back →
 *      the composer is empty.
 *   2. They SEND a prompt, the turn fails (rate limit, content filter, network,
 *      or a crash mid-stream) → the text is gone from the composer AND the send
 *      never produced a usable turn, so it isn't recoverable from history
 *      either.
 *
 * The in-memory `ChatViewModel._inputText` survives navigation while the VM is
 * cached, but NOT process death — and the laggy/oversized sessions this app is
 * used for are exactly the ones that get killed. So the draft is mirrored to
 * SharedPreferences (survives process death, cheap, no schema/migration) keyed
 * by session id.
 *
 * On send we DON'T just clear the draft — we move it to a `lastSent` slot. If
 * the turn then fails, the caller restores `lastSent` into the composer so the
 * user's words are never silently lost. On a turn that succeeds, the caller
 * clears both slots.
 *
 * Kept tiny and Android-only (SharedPreferences); pure key math is trivial and
 * inlined. Values are the user's own text — never logged.
 */
object DraftStore {
    private const val PREFS = "minis_composer_drafts"
    private const val DRAFT_PREFIX = "draft."
    private const val SENT_PREFIX = "sent."

    /**
     * [T-session-purge] The text the composer held before the user started
     * editing a queued message. It used to live in its own preferences file
     * under "backup_<sessionId>", which meant a second mechanism for the
     * same kind of data - and one that nothing ever cleaned up.
     */
    private const val PREEDIT_PREFIX = "preedit."

    /** The old, separate preferences file the pre-edit backup used to live in. */
    private const val LEGACY_PREFS = "queue_edit_backup"
    private const val LEGACY_PREFIX = "backup_"

    /** Minimal string KV the pure [Logic] runs against (Context-free for tests). */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)

        /**
         * Every key currently held.
         *
         * The default is empty rather than abstract because an implementation
         * that cannot enumerate simply has nothing to sweep - and a sweep
         * that finds nothing leaves the store untouched. Callers that need
         * the sweep to do something must implement it.
         */
        fun keys(): Set<String> = emptySet()
    }

    /**
     * All key math and clear/move rules, testable without Android. The Context
     * overloads below are thin adapters — this module's unit tests run with no
     * Robolectric, so `getSharedPreferences` returns null and any Context-
     * touching test fails for reasons unrelated to the logic. Mirrors the
     * prefs-Logic split (the pattern PlanModePrefs uses).
     */
    object Logic {
        /**
         * Every key prefix this store owns. A key that does not start with
         * one of them is left alone by the orphan sweep.
         */
        private val OWNED_PREFIXES = listOf(DRAFT_PREFIX, SENT_PREFIX, PREEDIT_PREFIX, "minis_queue:")

        fun draftKey(sessionId: String) = DRAFT_PREFIX + sessionId
        fun sentKey(sessionId: String) = SENT_PREFIX + sessionId
        fun queueKey(sessionId: String) = "minis_queue:" + sessionId
        fun preeditKey(sessionId: String) = PREEDIT_PREFIX + sessionId

        fun savePreEdit(store: Store, sessionId: String, text: String) {
            if (sessionId.isEmpty()) return
            if (text.isEmpty()) store.remove(preeditKey(sessionId)) else store.put(preeditKey(sessionId), text)
        }

        fun loadPreEdit(store: Store, sessionId: String): String {
            if (sessionId.isEmpty()) return ""
            return store.get(preeditKey(sessionId)) ?: ""
        }

        fun clearPreEdit(store: Store, sessionId: String) {
            if (sessionId.isEmpty()) return
            store.remove(preeditKey(sessionId))
        }

        /**
         * [T-session-purge] Everything this store holds for one session.
         *
         * Deleting a session removed its row from the database and left
         * every one of these behind: the draft, the last-sent recovery
         * text, the queued prompt and the pre-edit backup. They are the
         * user's own words, and they accumulate for as long as the app is
         * installed.
         */
        fun clearSession(store: Store, sessionId: String) {
            if (sessionId.isEmpty()) return
            store.remove(draftKey(sessionId))
            store.remove(sentKey(sessionId))
            store.remove(queueKey(sessionId))
            store.remove(preeditKey(sessionId))
        }

        /**
         * [T-session-purge] Drop every key whose session is gone.
         *
         * The per-session delete path is not the only way a session can
         * disappear, and a store that has been leaking for months still
         * holds the old keys. This reclaims them: for each key this store
         * owns, the session id is read out of the key, and if that session
         * is not in [liveSessionIds] the key is removed.
         *
         * Keys this store does not own are left alone - the store is shared
         * with nothing else, but a future key prefix must be added to
         * [sessionIdOf] before its orphans can be reclaimed.
         */
        fun removeOrphans(store: Store, liveSessionIds: Set<String>) {
            for (key in store.keys()) {
                val sessionId = sessionIdOf(key) ?: continue
                if (sessionId !in liveSessionIds) store.remove(key)
            }
        }

        /**
         * The session id a key belongs to, or null when the key is not one
         * of ours.
         */
        fun sessionIdOf(key: String): String? {
            for (prefix in OWNED_PREFIXES) {
                if (key.startsWith(prefix)) return key.substring(prefix.length)
            }
            return null
        }

        fun saveDraft(store: Store, sessionId: String, text: String) {
            if (sessionId.isEmpty()) return
            if (text.isEmpty()) store.remove(draftKey(sessionId)) else store.put(draftKey(sessionId), text)
        }

        fun loadDraft(store: Store, sessionId: String): String {
            if (sessionId.isEmpty()) return ""
            return store.get(draftKey(sessionId)) ?: ""
        }

        fun markSent(store: Store, sessionId: String, text: String) {
            if (sessionId.isEmpty()) return
            store.remove(draftKey(sessionId))
            // A blank send leaves no recoverable backup — nothing to restore.
            if (text.isEmpty()) store.remove(sentKey(sessionId)) else store.put(sentKey(sessionId), text)
        }

        fun peekLastSent(store: Store, sessionId: String): String {
            if (sessionId.isEmpty()) return ""
            return store.get(sentKey(sessionId)) ?: ""
        }

        fun clearLastSent(store: Store, sessionId: String) {
            if (sessionId.isEmpty()) return
            store.remove(sentKey(sessionId))
        }

        fun migrate(store: Store, fromSessionId: String, toSessionId: String) {
            if (fromSessionId.isEmpty() || toSessionId.isEmpty() || fromSessionId == toSessionId) return
            store.get(draftKey(fromSessionId))?.let {
                store.put(draftKey(toSessionId), it); store.remove(draftKey(fromSessionId))
            }
            store.get(sentKey(fromSessionId))?.let {
                store.put(sentKey(toSessionId), it); store.remove(sentKey(fromSessionId))
            }
        }
    }

    /** SharedPreferences-backed Store; batches writes into one edit()/apply(). */
    private class PrefsStore(context: Context) : Store {
        private val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun get(key: String): String? = p.getString(key, null)
        override fun put(key: String, value: String) { p.edit().putString(key, value).apply() }
        override fun remove(key: String) { p.edit().remove(key).apply() }
        override fun keys(): Set<String> = p.all.keys.toSet()
    }

    private fun store(context: Context): Store = PrefsStore(context)

    /** Persist (or clear, when blank) the live composer draft for [sessionId]. */
    fun saveDraft(context: Context, sessionId: String, text: String) =
        Logic.saveDraft(store(context), sessionId, text)

    /** The saved draft for [sessionId], or "" when none. */
    fun loadDraft(context: Context, sessionId: String): String =
        Logic.loadDraft(store(context), sessionId)

    /**
     * Called at send time: move the draft into the `lastSent` backup and clear
     * the live draft. If the turn later fails, [peekLastSent] brings it back.
     */
    fun markSent(context: Context, sessionId: String, text: String) =
        Logic.markSent(store(context), sessionId, text)

    /** The last-sent text held for recovery, or "" when none. */
    fun peekLastSent(context: Context, sessionId: String): String =
        Logic.peekLastSent(store(context), sessionId)

    /** Turn succeeded — drop the recovery backup so it can't resurface later. */
    fun clearLastSent(context: Context, sessionId: String) =
        Logic.clearLastSent(store(context), sessionId)

    /**
     * When a draft-session (temporary id) is promoted to its real persisted id,
     * carry the draft/sent slots over so nothing is stranded under the old key.
     */
    fun migrate(context: Context, fromSessionId: String, toSessionId: String) =
        Logic.migrate(store(context), fromSessionId, toSessionId)

    /** [T-queue-persist] Persist the serialized prompt queue (id + text pairs). */
    fun saveQueue(context: Context, sessionId: String, json: String) {
        if (sessionId.isEmpty()) return
        val st = store(context)
        if (json.isEmpty() || json == "[]") st.remove(Logic.queueKey(sessionId))
        else st.put(Logic.queueKey(sessionId), json)
    }

    /** Load the serialized prompt queue, or "" when none. */
    fun loadQueue(context: Context, sessionId: String): String {
        if (sessionId.isEmpty()) return ""
        return store(context).get(Logic.queueKey(sessionId)) ?: ""
    }

    /** Clear the persisted queue (after the queue is consumed by the agent loop). */
    fun clearQueue(context: Context, sessionId: String) {
        if (sessionId.isEmpty()) return
        store(context).remove(Logic.queueKey(sessionId))
    }

    /** [T-session-purge] Persist the text the composer held before an edit. */
    fun savePreEdit(context: Context, sessionId: String, text: String) =
        Logic.savePreEdit(store(context), sessionId, text)

    /**
     * [T-session-purge] The pre-edit backup for [sessionId].
     *
     * The backup used to live in its own preferences file under
     * "backup_<sessionId>". Reading it here once and moving it into this
     * store makes the old file self-healing: the first read after the
     * upgrade finds the text, moves it, and drops the old copy.
     */
    fun loadPreEdit(context: Context, sessionId: String): String {
        if (sessionId.isEmpty()) return ""
        val st = store(context)
        val current = Logic.loadPreEdit(st, sessionId)
        if (current.isNotEmpty()) return current
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val old = legacy.getString(LEGACY_PREFIX + sessionId, null)
        if (old.isNullOrEmpty()) return ""
        Logic.savePreEdit(st, sessionId, old)
        legacy.edit().remove(LEGACY_PREFIX + sessionId).apply()
        return old
    }

    /** [T-session-purge] Drop the pre-edit backup for [sessionId]. */
    fun clearPreEdit(context: Context, sessionId: String) =
        Logic.clearPreEdit(store(context), sessionId)

    /** [T-session-purge] Everything this store holds for [sessionId]. */
    fun clearSession(context: Context, sessionId: String) =
        Logic.clearSession(store(context), sessionId)

    /** [T-session-purge] Drop the keys of sessions that no longer exist. */
    fun removeOrphans(context: Context, liveSessionIds: Set<String>) =
        Logic.removeOrphans(store(context), liveSessionIds)
}
