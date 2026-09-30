package com.openminis.app.data

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.LLMMessage

/**
 * [T-session-history-cache] Cross-open cache for the parsed LLM history.
 *
 * The problem (user 29.09: «когда открываешь тяжёлую сессию тоже не должно
 * подвисать»): loadSession re-reads EVERY row and re-parses every partsJson
 * on EVERY open/switch — a 4000-message session with tens of MB of tool
 * results pays seconds of IO + JSON parsing for the identical data it
 * parsed a minute ago when the user hopped away and back.
 *
 * Design:
 *  - LRU of 2 sessions (active + previous — the hop pattern). The ACTIVE
 *    session's entry is cheap: agentHistory already references the very
 *    same LLMMessage instances, so the extra footprint is the row entities
 *    of at most ONE other session.
 *  - Revalidation is belt AND suspenders:
 *      (a) write-path hooks — ChatRepository append/rewrite/delete calls
 *          invalidate() / invalidateMessage(), because ALL message writes
 *          flow through the repository (single chokepoint);
 *      (b) structural fingerprint — row count + MAX(created_at) queried
 *          cheaply at load; a stale entry (external writer, fork restore)
 *          is dropped, never served.
 *  - Immutability: LLMMessage is a data class and request-builders copy
 *    lists; nothing mutates the cached instances in place after the load.
 *  - The bounded UI WINDOW is always rebuilt fresh from the DB (cheap,
 *    always-correct); only the heavy full-history pass is cached.
 */
object SessionHistoryCache {

    data class Entry(
        val rows: List<MessageEntity>,
        val llmHistory: List<LLMMessage>,
        val rowCount: Int,
        val maxCreatedAt: Long,
    )

    private const val MAX_SESSIONS = 2

    /** Access-ordered LRU — most recently used last. */
    private val store = object : LinkedHashMap<String, Entry>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > MAX_SESSIONS
    }

    @Synchronized
    fun get(sessionId: String, expectRowCount: Int, expectMaxCreatedAt: Long): Entry? {
        val e = store[sessionId] ?: return null
        val valid = e.rowCount == expectRowCount && e.maxCreatedAt == expectMaxCreatedAt
        if (!valid) {
            store.remove(sessionId)
            return null
        }
        return e
    }

    @Synchronized
    fun put(
        sessionId: String,
        rows: List<MessageEntity>,
        llmHistory: List<LLMMessage>,
        rowCount: Int,
        maxCreatedAt: Long,
    ) {
        store[sessionId] = Entry(rows, llmHistory, rowCount, maxCreatedAt)
    }

    /**
     * [T-retry-instant] Tail-trim: a truncation that removed rows AFTER
     * keepCount leaves the prefix identical — keep it, drop the tail.
     * Returns true when a matching entry existed (trim applied, or the cut
     * was longer than the cache = the surviving prefix is unchanged);
     * false = caller falls back to invalidate(). Correctness: the next
     * open's fingerprint revalidation (count + MAX(created_at) read live
     * from the DB) still arbitrates — a wrong trim can never serve stale
     * rows.
     */
    @Synchronized
    fun trimTail(sessionId: String, keepCount: Int): Boolean {
        val entry = store[sessionId] ?: return false
        if (keepCount <= 0) {
            store.remove(sessionId)
            return true
        }
        if (keepCount >= entry.rows.size) {
            // The cut removed rows the cache never held; the surviving
            // prefix is unchanged — plain hit semantics.
            return true
        }
        val trimmedRows = ArrayList<MessageEntity>(keepCount)
        for (i in 0 until keepCount) trimmedRows.add(entry.rows[i])
        var max = -1L
        for (r in trimmedRows) if (r.createdAt > max) max = r.createdAt
        store[sessionId] = Entry(
            trimmedRows,
            ArrayList(entry.llmHistory.subList(0, keepCount)),
            keepCount,
            max,
        )
        return true
    }

    @Synchronized
    fun invalidate(sessionId: String) {
        store.remove(sessionId)
    }

    /**
     * Rewrite path: [ChatRepository.updateMessageParts] knows only the
     * MESSAGE id — drop whichever cached session owns that row (linear id
     * scan over at most 2 cached lists is trivial; correctness beats a
     * reverse index here).
     */
    @Synchronized
    fun invalidateMessage(messageId: String) {
        val victims = store.entries.filter { (_, e) ->
            e.rows.any { it.id == messageId }
        }
        for ((k, _) in victims) store.remove(k)
    }

    @Synchronized
    fun clear() = store.clear()
}
