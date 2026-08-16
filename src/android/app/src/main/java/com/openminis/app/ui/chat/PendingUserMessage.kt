package com.openminis.app.ui.chat

import java.util.UUID

/**
 * [T-optimistic-user-bubble] Pure helper for the optimistic user message that
 * appears the instant the user hits send, before `ensureSession` + attachment
 * preparation + the Room write have run.
 *
 * ## Why this exists
 *
 * `sendMessage` only appended the user's bubble to `_messages` AFTER the DB
 * row was persisted. On a laggy/oversized session that write is not instant, so
 * the composer cleared and — for a visible beat — the chat showed nothing:
 * the user's exact "отправил сообщение, а в чате его нет" report.
 *
 * The durable text backup (DraftStore) already guarantees the *text* is never
 * lost across process death; this closes the *visual* gap without weakening
 * that guarantee. The optimistic bubble carries a `pending_…` id; when the real
 * DB row commits, [reconcile] swaps the placeholder id for the persisted one so
 * downstream identity (retry / edit / compaction boundary lookups) stays exact.
 * If the turn fails before it ever persisted, [dropPending] removes the
 * placeholder and the DraftStore backup restores the text into the composer —
 * so the message is either visible-and-persisted or recoverable, never silently
 * gone.
 *
 * The id math is pure so the reconcile/drop algorithm is unit-tested without a
 * ViewModel or Android.
 */
object PendingUserMessage {
    const val ID_PREFIX = "pending_"

    /** Fresh placeholder id for an optimistic bubble. */
    fun newId(): String = ID_PREFIX + UUID.randomUUID()

    /** True when [id] is an un-persisted optimistic placeholder. */
    fun isPending(id: String): Boolean = id.startsWith(ID_PREFIX)

    /**
     * Replace the placeholder id [pendingId] with the persisted [realId] in an
     * id list, preserving order and touching nothing else. If the placeholder
     * is absent (already reconciled / never added) the list is returned
     * unchanged — the caller then appends the real row so a race can never
     * drop the message.
     */
    fun reconcile(ids: List<String>, pendingId: String, realId: String): List<String> =
        if (ids.contains(pendingId)) ids.map { if (it == pendingId) realId else it } else ids

    /** Whether [reconcile] would have found the placeholder to swap. */
    fun contains(ids: List<String>, pendingId: String): Boolean = ids.contains(pendingId)

    /** Remove the placeholder id (turn failed before it persisted). */
    fun dropPending(ids: List<String>, pendingId: String): List<String> =
        ids.filterNot { it == pendingId }
}
