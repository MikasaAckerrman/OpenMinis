package com.openminis.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * [B31-session-list-perf] The fast Long-comparison bucketing must be
 * INDISTINGUISHABLE from the old per-session Calendar algorithm it
 * replaced: the differential sweep walks 400 days of timestamps (crossing
 * both DST transitions of the window) and requires equality at every
 * step. Boundary edges pin the old floor/strict semantics exactly.
 */
class SessionDateBoundariesTest {

    // The OLD algorithm, verbatim (now pinned to a deterministic instant).
    private fun referencePeriod(timestamp: Long, now: Calendar): DatePeriod {
        val cal = (now.clone() as Calendar).apply { timeInMillis = timestamp }
        if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        ) return DatePeriod.TODAY
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
        ) return DatePeriod.YESTERDAY
        val diffDays = TimeUnit.MILLISECONDS.toDays(now.timeInMillis - timestamp)
        if (diffDays < 7) return DatePeriod.THIS_WEEK
        val monthAgo = (now.clone() as Calendar).apply { add(Calendar.MONTH, -1) }.timeInMillis
        if (timestamp > monthAgo) return DatePeriod.THIS_MONTH
        return DatePeriod.EARLIER
    }

    private fun boundariesAt(now: Calendar) = SessionDateBoundaries.compute(now)

    @Test
    fun `fast bucketing matches the original calendar algorithm`() {
        val now = Calendar.getInstance().apply {
            set(2026, 10, 7, 15, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val b = boundariesAt(now)
        var ts = now.timeInMillis - TimeUnit.DAYS.toMillis(400)
        var checked = 0
        while (ts <= now.timeInMillis) {
            assertEquals(
                "ts=$ts",
                referencePeriod(ts, now),
                datePeriodFast(ts, b.todayStart, b.yesterdayStart, b.weekBoundary, b.monthBoundary),
            )
            checked++
            ts += TimeUnit.HOURS.toMillis(7)
        }
        assertTrue("sweep too small: $checked", checked > 1000)
    }

    @Test
    fun `boundary edges carry the old floor and strict-gt semantics`() {
        val now = Calendar.getInstance().apply {
            set(2026, 10, 7, 15, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val b = boundariesAt(now)
        fun fast(ts: Long) = datePeriodFast(ts, b.todayStart, b.yesterdayStart, b.weekBoundary, b.monthBoundary)

        assertEquals(DatePeriod.TODAY, fast(now.timeInMillis))
        assertEquals(DatePeriod.TODAY, fast(b.todayStart))
        // Midnight boundaries, not rolling 24h: 1ms before today's midnight
        // is still Yesterday's calendar day.
        assertEquals(DatePeriod.YESTERDAY, fast(b.todayStart - 1))
        assertEquals(DatePeriod.YESTERDAY, fast(b.yesterdayStart))
        // Exactly now-7d: floor(diffDays) = 7 is NOT < 7 — falls through to
        // the month check, exactly like the old code.
        assertEquals(DatePeriod.THIS_MONTH, fast(b.weekBoundary))
        assertEquals(DatePeriod.THIS_WEEK, fast(b.weekBoundary + 1))
        // Exactly now-1month: the old check was strict `timestamp > monthAgo`.
        assertEquals(DatePeriod.EARLIER, fast(b.monthBoundary))
        assertEquals(DatePeriod.THIS_MONTH, fast(b.monthBoundary + 1))
    }
}
