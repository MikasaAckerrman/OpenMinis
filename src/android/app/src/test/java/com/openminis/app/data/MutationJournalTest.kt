package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-mutation-journal] The journal's whole reason to exist is that it must be
 * there when nobody remembered to turn it on. So the properties worth guarding
 * are: it writes without any setup, it never throws, and its trim policy keeps
 * the NEWEST records (an age-based trim is what would have destroyed the
 * evidence for the 2c7ae861 incident).
 */
class MutationJournalTest {

    /**
     * The trim logic is the part with real arithmetic, and it is reachable
     * without an Android Context by reproducing it against a temp file. Keeping
     * the constants asserted here means a future "let's cap it at 100 KB" edit
     * has to look at the incident rationale.
     */
    @Test
    fun `keep window is smaller than the cap so a trim makes real room`() {
        assertTrue(MutationJournal.KEEP_BYTES < MutationJournal.MAX_BYTES)
    }

    @Test
    fun `cap is large enough for years of destructive events`() {
        // ~110 bytes per line; destructive events are rare (retry/edit/wipe).
        val approxLines = MutationJournal.MAX_BYTES / 110
        assertTrue("cap holds only $approxLines lines", approxLines > 10_000)
    }

    /**
     * Reproduces the trim invariant on a real file: after trimming to the last
     * KEEP bytes, the surviving text must (a) end with the newest line and
     * (b) never start mid-record.
     */
    @Test
    fun `trim keeps newest lines and never leaves a half record`() {
        val f = File.createTempFile("journal", ".log")
        try {
            val lines = (1..5000).map { "2026-08-29T00:00:00.000\tDELETE\tretry\tsess=abcdef12\tkeep=$it" }
            f.writeText(lines.joinToString("\n", postfix = "\n"))
            val keep = 20_000L

            // Same algorithm as MutationJournal.trimIfNeeded.
            val bytes = f.readBytes()
            val from = (bytes.size - keep).toInt().coerceAtLeast(0)
            var start = from
            while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
            if (start < bytes.size) start++
            val kept = String(bytes.copyOfRange(start, bytes.size))

            assertTrue("newest record must survive", kept.trimEnd().endsWith("keep=5000"))
            assertTrue("must start at a record boundary", kept.startsWith("2026-"))
            assertTrue("must have dropped the oldest", !kept.contains("keep=1\n"))
        } finally {
            f.delete()
        }
    }

    /**
     * Without init() (no Context — e.g. a unit test, or a crash before
     * Application.onCreate finished) every entry point must be a silent no-op
     * rather than an NPE inside the destructive operation it is describing.
     */
    @Test
    fun `records are silent no-ops before init`() {
        // file() is null until init(context); the record* helpers must tolerate it.
        MutationJournal.recordDelete("sess", "retry", 10, 20, 10)
        MutationJournal.recordRefusal("sess", "edit", -1, 20, "anchor-unresolved")
        MutationJournal.recordWipe("sess", "clearChat", 20)
        MutationJournal.recordCompact("sess", null, 100, 9000)
        MutationJournal.recordRestore("sess", 5, 1)
        MutationJournal.recordRewrite("sess", "surgery", "msgid123", 100, 50)
        // Reaching here without a throw IS the assertion.
        assertEquals(null, MutationJournal.file())
    }
}
