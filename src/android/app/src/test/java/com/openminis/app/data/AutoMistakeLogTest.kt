package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-auto-mistake] Contract tests: the MNL-style auto-capture writes ONE
 * compact line per failed tool call into today's daily log (the same file the
 * daily-memory fragment injects), throttles identical failures inside the
 * 60s window, and never throws (a capture failure must not break the tool
 * path it observes).
 */
class AutoMistakeLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var logDir: File? = null

    private fun freshLog(): File {
        val d = tmp.newFolder("m${System.nanoTime()}")
        logDir = d
        AutoMistakeLog.primeDirForTest(d)
        return d
    }

    private fun todayFile(): File {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return File(logDir, "$day.md")
    }

    @Test
    fun `capture writes one line into today's daily log`() {
        freshLog()
        val ok = AutoMistakeLog.capture("shell_execute", "Run tests", "exit 1: compilation failed", "sess-1234")
        assertTrue(ok)
        val text = todayFile().readText()
        assertTrue(text.contains("MISTAKE"))
        assertTrue(text.contains("shell_execute"))
        assertTrue(text.contains("compilation failed"))
        assertTrue(text.contains("sid=sess-123"))
        // ONE line — the daily-fragment budget is line-capped, density matters.
        assertEquals(1, text.trim().lines().size)
    }

    @Test
    fun `identical failure inside the throttle window is collapsed`() {
        freshLog()
        assertTrue(AutoMistakeLog.capture("shell_execute", "t", "same error", "s"))
        assertFalse("second identical capture must be throttled", AutoMistakeLog.capture("shell_execute", "t2", "same error", "s"))
        // A DIFFERENT tool or a different error is not throttled.
        assertTrue(AutoMistakeLog.capture("file_read", "t", "same error", "s"))
        assertTrue(AutoMistakeLog.capture("shell_execute", "t", "another error entirely", "s"))
    }

    @Test
    fun `newlines in the error are flattened to one line`() {
        freshLog()
        AutoMistakeLog.capture("shell_execute", "t", "line1\nline2\nline3", "s")
        val text = todayFile().readText()
        assertEquals("flattened", 1, text.trim().lines().size)
        assertTrue(text.contains("line1 line2"))
    }

    @Test
    fun `empty error is skipped`() {
        freshLog()
        assertFalse(AutoMistakeLog.capture("shell_execute", "t", "   ", "s"))
        assertFalse(todayFile().exists())
    }

    @Test
    fun `error excerpt is bounded`() {
        freshLog()
        AutoMistakeLog.capture("shell_execute", "t", "x".repeat(10_000), "s")
        val text = todayFile().readText()
        assertTrue("≤240 chars excerpt + formatting", text.length < 400)
    }

    @Test
    fun `unprimed log captures nothing and never throws`() {
        AutoMistakeLog.primeDirForTest(null)
        assertFalse(AutoMistakeLog.capture("shell_execute", "t", "error", "s"))
    }
}
