package com.openminis.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * [T-gc-churn] Deterministic guard for the AppLogger formatting fix.
 *
 * Before the fix every log line formatted the calendar TWICE through one
 * shared SimpleDateFormat: `dateFormat.format(now)` for the file name and
 * `timestampFormat.format(now)` for the stamp — on a path that runs for every
 * line of stdout, for every LogcatTailer line and for every log() call.
 *
 * The fix keeps a per-thread Formatters and caches the day label until the
 * epoch day actually rolls over, so a steady stream of log lines inside one
 * day formats the calendar once instead of twice per line.
 *
 * These assertions are exact (no flakiness): they check the cache semantics
 * directly on the class the production code now uses.
 */
class AppLoggerFormattersCacheTest {

    private fun millisFor(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        min: Int,
        sec: Int,
        ms: Int,
        tz: String = "UTC",
    ): Long {
        val tz0 = TimeZone.getTimeZone(tz)
        return Calendar.getInstance(tz0, Locale.US).apply {
            clear()
            set(year, month - 1, day, hour, min, sec)
            set(Calendar.MILLISECOND, ms)
        }.timeInMillis
    }

    @Test
    fun dayLabel_isFormattedOnceAndReusedWithinTheSameDay() {
        val f = Formatters()
        val morning = millisFor(2024, 3, 7, 9, 15, 0, 0)
        val evening = millisFor(2024, 3, 7, 21, 45, 30, 500)

        val first = f.dayLabel(morning)
        val second = f.dayLabel(evening)

        // Same day -> the very same String instance (no reformat, no new alloc).
        assertSame("day label must be cached within the same day", first, second)
        assertEquals("2024-03-07", first)
    }

    @Test
    fun dayLabel_changesExactlyWhenTheDayRollsOver() {
        val f = Formatters()
        val beforeMidnight = millisFor(2024, 3, 7, 23, 59, 59, 999)
        val afterMidnight = millisFor(2024, 3, 8, 0, 0, 0, 0)

        assertEquals("2024-03-07", f.dayLabel(beforeMidnight))
        assertEquals("2024-03-08", f.dayLabel(afterMidnight))
        // Rolling back re-formats rather than returning a stale label.
        assertEquals("2024-03-07", f.dayLabel(beforeMidnight))
    }

    @Test
    fun dayLabel_isMonotonicAcrossManySamplesOfTheSameDay() {
        val f = Formatters()
        val labels = (0 until 500).map { i ->
            f.dayLabel(millisFor(2024, 12, 25, 0, 0, 0, i))
        }
        assertEquals(1, labels.distinct().size)
        assertEquals("2024-12-25", labels.first())
    }

    @Test
    fun stamp_doesNotDependOnTheDayCache() {
        // The millisecond stamp is always re-formatted (it must change), while
        // the day label is cached. Both must agree on the day they describe.
        val f = Formatters()
        val t0 = millisFor(2024, 6, 1, 12, 0, 0, 0)
        val t1 = millisFor(2024, 6, 1, 12, 0, 0, 500)

        f.dayLabel(t0)
        f.dayLabel(t1)
        assertNotEquals(
            "the millisecond stamp must keep changing",
            f.stamp.format(java.util.Date(t0)),
            f.stamp.format(java.util.Date(t1)),
        )
        assertEquals("2024-06-01", f.dayLabel(t1))
    }
}