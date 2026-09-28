package com.openminis.app.tools

import java.security.MessageDigest

/**
 * [T-fileread-cache] Wishlist No.8: content-hash dedup for file_read.
 *
 * Long engineering sessions re-read the same large files to check a state
 * that usually did not change (ChatViewModel: ~500 KB per re-read). Every
 * re-read paid its full cost in the context window — the one resource the
 * whole session shares with the user's work. This cache returns a stub
 * when the file's content hash AND the exact request shape
 * (offset/lines/direction/max_length) match the previous read: the model
 * already holds the content, and "unchanged" is the fact it wanted.
 *
 * Semantics:
 *  - key = sessionId + path + request-shape. Different sessions and
 *    different read shapes never collide (re-reading with a new offset
 *    after an edit-less window is a NEW question, not a repeat).
 *  - The fingerprint is sha256 of the line-normalized FULL file content
 *    (FileReadTool reads all lines before slicing anyway). Any edit
 *    anywhere invalidates every cached shape for that path.
 *  - force_read bypasses the stub (the escape hatch lives in the tool's
 *    parameter surface, not here).
 *  - LRU, capped: a session hopping across a huge repo must not grow the
 *    map unbounded. Process death resets the cache — a fresh session has
 *    read nothing, so the first read is always full.
 */
object FileReadCache {

    data class Entry(
        val contentSha256: String,
        val readAtMs: Long,
    )

    private const val MAX_ENTRIES = 128

    private val store = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > MAX_ENTRIES
    }

    /** Key: sessionId + NUL + path + NUL + request-shape string. NUL keeps
     *  path characters from forging cross-field collisions. */
    fun key(sessionId: String, path: String, requestShape: String): String =
        "$sessionId\u0000$path\u0000$requestShape"

    /** SHA-256 of the content, hex; deterministic for line-normalized text. */
    fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * A cache hit requires BOTH the stored fingerprint and the passed one
     * to match: the entry exists for this exact request shape AND the file
     * content is byte-identical (normalized) to the last read. Anything
     * else — miss (the caller returns full content and re-records).
     */
    @Synchronized
    fun lookup(cacheKey: String, contentSha256: String): Entry? {
        val e = store[cacheKey] ?: return null
        return if (e.contentSha256 == contentSha256) e else null
    }

    /** Record (or refresh) the read for this exact request shape. */
    @Synchronized
    fun record(cacheKey: String, contentSha256: String, readAtMs: Long) {
        store[cacheKey] = Entry(contentSha256, readAtMs)
    }

    /** Test/teardown hook; production never calls this. */
    @Synchronized
    fun clear() = store.clear()
}
