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

    /** The service line the export writes into the file. */
    private val RANGE_IN_TEXT =
        Regex("RANGE\\s*[:\\-]?\\s*([A-Za-z0-9_-]{6,64})\\.\\.([A-Za-z0-9_-]{6,64})")

    /** How long a remembered export is re-announced instead of recomputed. */
    const val FRESH_MS = 15L * 60 * 1000

    /**
     * Sessions kept at once. Eight is more than anyone works with, and the
     * bound is what stops the index from growing into a slow scan.
     */
    const val MAX_TRACKED = 8

    const val INDEX_KEY = "exportCacheIndex"

    data class Entry(
        val sessionId: String,
        val uri: String,
        val atMs: Long,
        val where: String,
        val firstDbId: String?,
        val lastDbId: String?,
    )

    /**
     * The range of the export, as the import side validates it.
     *
     * [T-compact-fast-path-range] The re-export fast path announces an
     * existing file. It used to leave this chat's range keys untouched, so the
     * export looked fine and the import then refused with "Активный экспорт -
     * из другой сессии" because it requires the range of this chat. The pref
     * write stays in the ViewModel (this object is pure), so the only decision
     * here is "which range belongs to this file".
     */
    fun rangeOf(entry: Entry): Pair<String, String>? {
        val first = entry.firstDbId
        val last = entry.lastDbId
        if (first.isNullOrBlank() || last.isNullOrBlank()) return null
        return first to last
    }

    /**
     * [T-compact-range-from-artifact] The range carried by the artifact.
     *
     * The export writes a service line - "(Служебная строка, не изменяй:
     * RANGE <first>..<last>)" - into the file it produces, and the export
     * prompt asks the external model to keep that line. The file therefore
     * knows which messages it was made from even when the remembered export
     * is gone: another install, an export made before the range was
     * published, or a chat that never exported at all.
     *
     * The value returned here is a CANDIDATE, not an authority. The apply
     * step recomputes the live range from history and refuses any drift, so
     * a foreign or edited range cannot fold the wrong messages
     * (see ExternalCompactionPlan).
     *
     * "?" is the placeholder the export writes when a range end had no
     * persisted id; it is not a range and must not be read as one.
     */
    fun rangeFromText(text: String): Pair<String, String>? {
        val match = RANGE_IN_TEXT.find(text) ?: return null
        val first = match.groupValues[1]
        val last = match.groupValues[2]
        return first to last
    }

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
     * The entry to re-announce for [sessionId], or null when there is none, it
     * is stale, or it belongs to another chat.
     *
     * The ownership check is inside this function on purpose. Keys are already
     * namespaced per session, which makes a foreign entry impossible to read
     * in the first place - but "impossible because the caller passed the right
     * key" is exactly the kind of guarantee that stops holding when someone
     * adds a fourth call site. Carrying the owner in the entry and comparing it
     * here means the reported bug cannot come back through a new caller.
     */
    fun pickFresh(entry: Entry?, sessionId: String, nowMs: Long): Entry? {
        if (entry == null) return null
        if (entry.sessionId != sessionId) return null
        if (entry.uri.isBlank()) return null
        if (entry.firstDbId == null || entry.lastDbId == null) return null
        return if (isFresh(entry.atMs, nowMs)) entry else null
    }
}