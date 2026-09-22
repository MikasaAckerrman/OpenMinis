package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-auto-mode-verify] The machine half of completion, proven pure:
 * block parsing, the retry→replan→disarm state machine, shell quoting.
 */
class AutoModeVerificationTest {

    // ── parsing ────────────────────────────────────────────────────────────

    @Test
    fun `full block parses`() {
        val text = """
            Сделал рефакторинг.

            VERIFY:
            files: /tmp/om-work/A.kt, /tmp/om-work/B.kt
            absent: /tmp/old.kt
            cmd: ls /tmp/om-work

            TASK_COMPLETE
        """.trimIndent()
        val c = AutoModeVerification.parse(text)
        assertNotNull(c)
        c!!
        assertEquals(listOf("/tmp/om-work/A.kt", "/tmp/om-work/B.kt"), c.filesExist)
        assertEquals(listOf("/tmp/old.kt"), c.filesAbsent)
        assertEquals("ls /tmp/om-work", c.command)
    }

    @Test
    fun `partial block with only cmd parses`() {
        val c = AutoModeVerification.parse("done\nVERIFY:\ncmd: test -d /var/minis")
        assertNotNull(c)
        assertEquals("test -d /var/minis", c!!.command)
        assertTrue(c.filesExist.isEmpty())
    }

    @Test
    fun `no block returns null - research turns degrade gracefully`() {
        assertNull(AutoModeVerification.parse("Проанализировал вопрос, вывод: ..."))
        assertNull(AutoModeVerification.parse(""))
        // Block with no usable keys is also null, not an empty criteria set.
        assertNull(AutoModeVerification.parse("VERIFY:\n(забыл заполнить)"))
    }

    @Test
    fun `paths with spaces and quotes survive parsing`() {
        val c = AutoModeVerification.parse(
            "VERIFY:\nfiles: /tmp/my file.txt, '/tmp/quoted.kt'",
        )
        assertNotNull(c)
        assertEquals(listOf("/tmp/my file.txt", "/tmp/quoted.kt"), c!!.filesExist)
    }

    @Test
    fun `cmd containing colons parses whole line`() {
        val c = AutoModeVerification.parse("VERIFY:\ncmd: grep -c 'a:b' file.txt")
        assertEquals("grep -c 'a:b' file.txt", c!!.command)
    }

    // ── state machine ──────────────────────────────────────────────────────

    @Test
    fun `first failures retry the same approach`() {
        assertEquals(AutoModeVerification.Step.RETRY_SAME, AutoModeVerification.nextStep(1, 0))
        assertEquals(AutoModeVerification.Step.RETRY_SAME, AutoModeVerification.nextStep(2, 0))
    }

    @Test
    fun `third consecutive failure forces a replan`() {
        assertEquals(AutoModeVerification.Step.REPLAN, AutoModeVerification.nextStep(3, 0))
        assertEquals(AutoModeVerification.Step.REPLAN, AutoModeVerification.nextStep(5, 2))
    }

    @Test
    fun `exhausted replans disarm honestly`() {
        assertEquals(AutoModeVerification.Step.DISARM, AutoModeVerification.nextStep(3, 3))
        assertEquals(AutoModeVerification.Step.DISARM, AutoModeVerification.nextStep(9, 3))
    }

    // ── shell quoting ──────────────────────────────────────────────────────

    @Test
    fun `plain path is single-quoted`() {
        assertEquals("test -e '/tmp/a.kt'", AutoModeVerification.existsCommand("/tmp/a.kt"))
        assertEquals("! test -e '/tmp/x'", AutoModeVerification.notExistsCommand("/tmp/x"))
    }

    @Test
    fun `embedded quote is escaped posix-style`() {
        assertEquals("'it'\\''s'", AutoModeVerification.shellQuote("it's"))
        assertEquals("! test -e '/tmp/a'\\''b'", AutoModeVerification.notExistsCommand("/tmp/a'b"))
    }

    @Test
    fun `failure report lists every failed check`() {
        val r = AutoModeVerification.failureReport(listOf("файл не существует: /a", "cmd exit 1"))
        assertTrue(r.contains("verification FAILED"))
        assertTrue(r.contains("/a"))
        assertTrue(r.contains("cmd exit 1"))
    }
}
