package com.openminis.app.service

import android.content.Context
import com.openminis.app.data.DraftStore
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.ui.chat.ChatViewModelStore

/**
 * [T-session-purge] Everything that belongs to one session, outside the
 * database.
 *
 * Deleting a session removed its row from the database - and left every
 * other trace of it behind:
 *
 *   - the composer draft,
 *   - the last-sent recovery text,
 *   - the queued prompt,
 *   - the text the composer held before a queued message was edited,
 *   - the edit marks for that session's messages,
 *   - the offload permissions the user granted or denied for it,
 *   - the skill and MCP overrides chosen for it,
 *   - the media folder.
 *
 * The cleanup functions for most of these already existed. None of them was
 * ever called from the delete path, so they accumulated for as long as the
 * app was installed - and they are the user's own words.
 *
 * Every path that deletes a session must call [purge]. The database delete
 * and the purge belong together: one removes the conversation, the other
 * removes what the app still remembers about it.
 */
object SessionPurge {

    /**
     * Remove every trace of [sessionId] that is not in the database.
     *
     * Safe to call for a session that does not exist, and safe to call twice.
     * Each step is independent: one failing store must not stop the others
     * from being cleaned, because the point of the call is to leave nothing
     * behind.
     */
    fun purge(context: Context, sessionId: String) {
        if (sessionId.isEmpty()) return

        // In-memory state. These are cheap and cannot fail.
        runCatching { ChatViewModelStore.release(sessionId) }
        runCatching { SessionBadgeStore.clear(sessionId) }
        runCatching { MessageEditStore.clearSession(sessionId) }
        runCatching { OffloadPermissionManager.clearSessionGrants(sessionId) }

        // Durable per-session text: draft, last-sent, queue, pre-edit backup.
        runCatching { DraftStore.clearSession(context, sessionId) }

        // Per-session overrides the user chose for this conversation, and
        // the media the conversation produced.
        val app = context.applicationContext as? MinisApp
        runCatching { app?.skillRepository?.clearSessionOverrides(sessionId) }
        runCatching { app?.mcpRepository?.clearSessionOverrides(sessionId) }
        runCatching { com.openminis.app.data.storage.MediaStore(context).deleteSessionMedia(sessionId) }
    }

    /**
     * [T-session-purge] Drop the leftovers of sessions that no longer exist.
     *
     * The delete path is not the only way a session can disappear, and a
     * store that has been leaking for months still holds the old keys. The
     * live session ids are the ones the database still knows about; every
     * key belonging to anything else is removed.
     *
     * Cheap enough to run when the session list loads - it touches only the
     * preferences file, not the database.
     */
    fun sweepOrphans(context: Context, liveSessionIds: Set<String>) {
        if (liveSessionIds.isEmpty()) return
        runCatching { DraftStore.removeOrphans(context, liveSessionIds) }
    }
}
