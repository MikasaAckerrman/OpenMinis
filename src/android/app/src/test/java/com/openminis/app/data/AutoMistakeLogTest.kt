package com.openminis.app.data

import com.openminis.app.data.repository.MemoryRepository
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
 * [T-auto-mistake] Contract tests. The v2 design routes every capture through
 * a REAL MemoryRepository on a temp dir — pinning the three properties the v1
 * design broke:
 *   1. PLACEMENT: the entry lands at the HEAD of today's file (newest-first —
 *      the daily fragment reads head lines; a tail-append would never inject).
 *   2. FRAMING: the repo's <!-- ts --> entry framing wraps the mistake line —
 *      one content line per entry, the fragment's line budget stays dense.
 *   3. SAME-WRITE-PATH: the model's own memory_write interleaves correctly
 *      (both go through the repo's read-prepend-write).
 * Plus the throttle semantics and the never-throws contract.
 */
class AutoMistakeLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var repoDir: File? = null

    private fun freshRepo(): MemoryRepository {
        AutoMistakeLog.resetForTest()
        val d = tmp.newFolder("m${System.nanoTime()}")
        repoDir = d
        return MemoryRepository(d)
    }

    private fun todayFile(): File {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return File(repoDir, "$day.md")
    }

    @Test
    fun `capture lands at the HEAD of today's log (newest-first placement)`() {
        val repo = freshRepo()
        repo.writeMemory("manual note written BEFORE the mistake")
        val ok = AutoMistakeLog.capture(repo, "shell_execute", "Run tests", "exit 1: compilation failed", "sess-1234")
        assertTrue(ok)
        val text = todayFile().readText()
        val mistakeIdx = text.indexOf("MISTAKE")
        val noteIdx = text.indexOf("manual note written BEFORE")
        assertTrue(mistakeIdx >= 0)
        assertTrue("mistake must be ABOVE the older note (newest-first)", mistakeIdx < noteIdx)
        assertTrue(text.contains("shell_execute"))
        assertTrue(text.contains("compilation failed"))
        assertTrue(text.contains("sid=sess-123"))
        // The mistake entry is ONE content line.
        val entryBlock = text.substring(0, noteIdx)
        assertEquals(1, entryBlock.trim().lines().size)
    }

    @Test
    fun `identical failure inside the throttle window is collapsed`() {
        val repo = freshRepo()
        assertTrue(AutoMistakeLog.capture(repo, "shell_execute", "t", "same error", "s"))
        assertFalse("second identical capture must be throttled", AutoMistakeLog.capture(repo, "shell_execute", "t2", "same error", "s"))
        // A DIFFERENT tool or a different error is not throttled.
        assertTrue(AutoMistakeLog.capture(repo, "file_read", "t", "same error", "s"))
        assertTrue(AutoMistakeLog.capture(repo, "shell_execute", "t", "another error entirely", "s"))
    }

    @Test
    fun `newlines in the error are flattened to one content line`() {
        val repo = freshRepo()
        AutoMistakeLog.capture(repo, "shell_execute", "t", "line1\nline2\nline3", "s")
        val text = todayFile().readText()
        assertEquals("one content line per entry", 1, text.trim().lines().size)
        assertTrue(text.contains("line1 line2"))
    }

    @Test
    fun `empty error is skipped`() {
        val repo = freshRepo()
        assertFalse(AutoMistakeLog.capture(repo, "shell_execute", "t", "   ", "s"))
        assertFalse(todayFile().exists())
    }

    @Test
    fun `error excerpt is bounded`() {
        val repo = freshRepo()
        AutoMistakeLog.capture(repo, "shell_execute", "t", "x".repeat(10_000), "s")
        val text = todayFile().readText()
        assertTrue("≤240 chars excerpt + formatting", text.length < 400)
    }
}
