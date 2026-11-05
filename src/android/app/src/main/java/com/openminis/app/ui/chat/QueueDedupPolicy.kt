package com.openminis.app.ui.chat

/**
 * [B-dedup-acceptor] Pure acceptor-side dedup split for the persisted prompt
 * queue (extracted from drainQueuedPrompts / injectQueuedPromptsAsNewTurn /
 * the session-restore block — the test seam for queue-acceptance-spec
 * scenario B, see REVIEWS/evidence/20261104/queue-acceptance-spec.md).
 *
 * INVARIANT (loss-avoidance): this policy NEVER invents a drop on its own —
 * an entry lands in [Split.alreadyAccepted] only when the CALLER proved the
 * id is durable in the DB (a message row whose source_prompt_ids carries it,
 * written by the same insert as the row). On any doubt (DB error, empty
 * result, unknown id) the caller passes an empty accepted set and every
 * entry stays fresh — the old at-least-once behavior, a possible duplicate,
 * never a loss. A dropped entry is by definition already delivered.
 *
 * promptIds are single-use (fresh "queued_<ts>_<rand>" per enqueue, fresh
 * "wake_<ts>_<rand>" per wake) and no path re-enqueues a used id, so a false
 * positive here is structurally impossible — unlike the removed 08.10
 * TEXT-dedup ([T-queue-dedup-killer]) that matched a legit re-typed message
 * against the DB's last user row.
 */
object QueueDedupPolicy {

    /**
     * [fresh] — entries to accept now, in FIFO order.
     * [alreadyAccepted] — entries whose message row is already durable;
     * the caller removes them from the queue and persists the removal.
     */
    data class Split(
        val fresh: List<QueuedPrompt>,
        val alreadyAccepted: List<QueuedPrompt>,
    )

    fun split(entries: List<QueuedPrompt>, acceptedIds: Set<String>): Split {
        if (acceptedIds.isEmpty() || entries.isEmpty()) return Split(entries, emptyList())
        return Split(
            fresh = entries.filter { it.id !in acceptedIds },
            alreadyAccepted = entries.filter { it.id in acceptedIds },
        )
    }
}
