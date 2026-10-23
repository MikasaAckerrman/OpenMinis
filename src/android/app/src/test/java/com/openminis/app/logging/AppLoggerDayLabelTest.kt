package com.openminis.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Guards the local-day key used by [AppLogger.Formatters].
 *
 * The day label decides which daily file a log line lands in
 * (`minis-$date.log`), so it must be the LOCAL date. Keying the cache on
 * UTC days invalidated it at UTC midnight: between local midnight and UTC
 * midnight the cached label was yesterday's date, and the log file was
 * named after the wrong day. At UTC+4 that window is four hours a day; at
 * negative offsets up to nineteen hours.
 *
 * The zone is passed in explicitly, so the test never mutates the JVM
 * default and can pin any zone it needs.
 */
class AppLoggerDayLabelTest {

    private fun millisAt(
        y: Int,
        m: Int,
        d: Int,
        h: Int,
        min: Int,
        tz: TimeZone,
    ): Long {
        val cal = Calendar.getInstance(tz, Locale.US)
        cal.clear()
        cal.set(y, m, d, h, min, 0)
        return cal.timeInMillis
    }

    private fun formatted(millis: Long, tz: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = tz }
            .format(Date(millis))

    @Test
    fun labelFollowsTheLocalDateNotTheUtcDate() {
        val tz = TimeZone.getTimeZone("Europe/Samara")
        val utc = TimeZone.getTimeZone("UTC")
        val f = AppLogger.Formatters(tz)

        val localEarly = millisAt(2026, Calendar.OCTOBER, 23, 0, 30, tz)

        assertNotEquals(
            "fixture must sit on two different calendar days",
            formatted(localEarly, utc),
            formatted(localEarly, tz),
        )
        assertEquals(
            "day label must be the LOCAL date",
            formatted(localEarly, tz),
            f.dayLabel(localEarly),
        )
    }

    @Test
    fun labelIsStableWithinTheSameLocalDay() {
        val tz = TimeZone.getTimeZone("Europe/Samara")
        val f = AppLogger.Formatters(tz)
        val morning = millisAt(2026, Calendar.OCTOBER, 23, 1, 0, tz)
        val evening = millisAt(2026, Calendar.OCTOBER, 23, 23, 30, tz)
        assertEquals(f.dayLabel(morning), f.dayLabel(evening))
    }

    @Test
    fun labelRollsOverAtLocalMidnight() {
        val tz = TimeZone.getTimeZone("Europe/Samara")
        val f = AppLogger.Formatters(tz)
        val beforeMidnight = millisAt(2026, Calendar.OCTOBER, 23, 23, 59, tz)
        val afterMidnight = millisAt(2026, Calendar.OCTOBER, 24, 0, 1, tz)
        assertNotEquals(
            "the label must change at LOCAL midnight",
            f.dayLabel(beforeMidnight),
            f.dayLabel(afterMidnight),
        )
    }

    @Test
    fun labelRollsOverAtLocalMidnightInANegativeOffsetZone() {
        val tz = TimeZone.getTimeZone("America/New_York")
        val f = AppLogger.Formatters(tz)
        val beforeMidnight = millisAt(2026, Calendar.OCTOBER, 23, 23, 59, tz)
        val afterMidnight = millisAt(2026, Calendar.OCTOBER, 24, 0, 1, tz)
        assertNotEquals(
            "the label must change at LOCAL midnight",
            f.dayLabel(beforeMidnight),
            f.dayLabel(afterMidnight),
        )
        assertEquals(formatted(afterMidnight, tz), f.dayLabel(afterMidnight))
    }

    @Test
    fun labelMatchesTheFormatterZoneForTheDefaultZone() {
        val tz = TimeZone.getDefault()
        val f = AppLogger.Formatters(tz)
        val now = System.currentTimeMillis()
        assertEquals(formatted(now, tz), f.dayLabel(now))
    }
}
