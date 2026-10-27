package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-compact-fast-path-range] The re-export fast path announces an existing
 * file. It must publish the range of that file for this chat, otherwise the
 * import side (which requires the range) refuses with "Активный экспорт - из
 * другой сессии" although the export itself worked.
 *
 * The pref write stays in the ViewModel, so only the decision is pinned here.
 */
class ExternalExportCacheRangeTest {

    private fun entry(firstDbId: String?, lastDbId: String?) = ExternalExportCache.Entry(
        sessionId = "chat-A",
        uri = "content://export/1",
        atMs = 1_700_000_000_000L,
        where = "x",
        firstDbId = firstDbId,
        lastDbId = lastDbId,
    )

    @Test
    fun anEntryWithARangeYieldsTheRangeToPublish() {
        assertEquals(
            "10" to "42",
            ExternalExportCache.rangeOf(entry("10", "42")),
        )
    }

    @Test
    fun anEntryWithoutARangeYieldsNothingToPublish() {
        assertNull(ExternalExportCache.rangeOf(entry(null, "42")))
        assertNull(ExternalExportCache.rangeOf(entry("10", null)))
        assertNull(ExternalExportCache.rangeOf(entry(null, null)))
    }

    @Test
    fun aBlankRangeIsNotARange() {
        assertNull(ExternalExportCache.rangeOf(entry("  ", "42")))
        assertNull(ExternalExportCache.rangeOf(entry("10", "  ")))
    }
}
