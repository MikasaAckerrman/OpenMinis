package com.openminis.app.ui.chat

/**
 * Per-session remember-slot for the last external-compaction export.
 *
 * [T-export-cache-not-session-scoped] This used to be five flat keys in a
 * single SharedPreferences ("lastExportUri", "exportedAtMs", "sessionId",
 * "firstDbId", "lastDbId"), so there was exactly one export remembered across
 * the whole app. The write side even stored the owning session id and
 * documented that "an export from ANOTHER chat must never fold here" - and the
 * import side did check it - but the export side never compared it. Two chats
 * exporting back to back therefore blocked each other: the second was told
 * "свежий экспорт уже готов (N мин. назад)" about a file that belonged to a
 * chat it had never exported.
 *
 * Keys are namespaced per session and an index records which sessions are
 * tracked, so:
 *
 *  - re-exporting chat A after exporting chat B still finds A's own file;
 *  - exporting B does not destroy the range ids A's import needs;
 *  - both the export gate and the import range check read the same session.
 *
 * Only the pure parts live here. SharedPreferences access stays in the
 * ViewModel, which keeps the decisions testable without Android.
 */
internal object ExternalExportCache {
    /** How long a remembered export is re-announced instead of recomputed. */
    const val FRESH_MS = 15L * 60 * 1000

    /**
     * Sessions kept at once. Eight is more than anyone works with, and the
     * bound is what stops the index from growing into a slow scan.
     */
    const val MAX_TRACKED = 8

    const val INDEX_KEY = "exportCacheIndex"

    data class Entry(
        val uri: String,
        val atMs: Long,
        val where: String,
        val firstDbId: String?,
        val lastDbId: String?,
    )

    fun uriKey(sessionId: String) = "exportUri::$sessionId"
    fun atMsKey(sessionId: String) = "exportAtMs::$sessionId"
    fun whereKey(sessionId: String) = "exportWhere::$sessionId"
    fun firstDbIdKey(sessionId: String) = "exportFirstDbId::$sessionId"
    fun lastDbIdKey(sessionId: String) = "exportLastDbId::$sessionId"

    /** All keys belonging to [sessionId], for removal. */
    fun keysFor(sessionId: String): List<String> = listOf(
        uriKey(sessionId),
        atMsKey(sessionId),
        whereKey(sessionId),
        firstDbIdKey(sessionId),
        lastDbIdKey(sessionId),
    )

    /**
     * Whether an export this old is still re-announced.
     *
     * The lower bound matters: a timestamp from the future means the clock
     * moved backwards (or the pref file was edited), and treating that as
     * "fresh forever" would pin the user to a stale file until the clock
     * catches up. Stale means recompute.
     */
    fun isFresh(atMs: Long, nowMs: Long): Boolean {
        val age = nowMs - atMs
        return age >= 0 && age < FRESH_MS
    }

    fun parseIndex(raw: String?): List<String> =
        raw?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun formatIndex(ids: List<String>): String = ids.joinToString("\n")

    /**
     * [sessionId] as the most recently used entry, deduplicated, oldest
     * dropped once [MAX_TRACKED] is exceeded.
     */
    fun remember(previous: List<String>, sessionId: String): List<String> {
        if (sessionId.isBlank()) return previous.takeLast(MAX_TRACKED)
        return (previous.filter { it.isNotBlank() && it != sessionId } + sessionId)
            .takeLast(MAX_TRACKED)
    }

    /** Sessions that were tracked before but are no longer in [kept]. */
    fun evicted(previous: List<String>, kept: List<String>): List<String> =
        previous.filter { it.isNotBlank() && it !in kept }

    /**
     * The entry to re-announce for [sessionId], or null when there is none or
     * it is stale. Kept separate from the pref read so the decision can be
     * tested on its own.
     */
    fun pickFresh(entry: Entry?, sessionId: String, nowMs: Long): Entry? {
        if (entry == null) return null
        if (entry.uri.isBlank()) return null
        if (entry.firstDbId == null || entry.lastDbId == null) return null
        return if (isFresh(entry.atMs, nowMs)) entry else null
    }
}