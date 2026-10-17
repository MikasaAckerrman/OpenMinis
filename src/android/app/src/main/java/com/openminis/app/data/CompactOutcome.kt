package com.openminis.app.data

/**
 * [T-compact-integrity] Result of a summarization pass, carrying the number of
 * windows that never produced text.
 *
 * Why this exists: the pass used to return a bare `String`, so a partial
 * failure (some windows died even after the sequential repair pass) was
 * indistinguishable from a complete one. The caller then wrote the compaction
 * marker over the WHOLE range, and the text of the lost windows disappeared
 * from what the model sees — the data stayed in the DB, but the request no
 * longer contained it. Silent, unrecoverable context loss.
 *
 * With [holes] > 0 the caller MUST NOT commit a marker: it reports the exact
 * numbers and leaves the history untouched. Re-running is cheap because
 * already-summarized windows are served from the on-disk window cache.
 */
data class CompactOutcome(
    val summary: String,
    val holes: Int,
    val windows: Int,
) {
    val complete: Boolean get() = holes == 0

    /** Human-readable "сжато X из Y частей". */
    fun progressText(): String =
        "сжато ${(windows - holes).coerceAtLeast(0)} из $windows частей"
}

/**
 * Thrown when a pass finished with holes. Distinct from provider failures so
 * the UI can explain "history untouched, retry is cheap" instead of showing a
 * generic compact error.
 */
class CompactPartialFailure(
    val holes: Int,
    val windows: Int,
) : Exception("compact partial: ${windows - holes} of $windows windows")
