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

    /** Minimal string KV the pure [Logic] runs against (Context-free for tests). */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    /**
     * All key math and clear/move rules, testable without Android. The Context
     * overloads below are thin adapters — this module's unit tests run with no
     * Robolectric, so `getSharedPreferences` returns null and any Context-
     * touching test fails for reasons unrelated to the logic. Mirrors the
     * AgentModePrefs.Logic split.
     */
    object Logic {
        fun draftKey(sessionId: String) = DRAFT_PREFIX + sessionId
        fun sentKey(sessionId: String) = SENT_PREFIX + sessionId

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
}
