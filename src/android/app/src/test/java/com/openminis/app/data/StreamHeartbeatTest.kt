package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-partial-turn-durability] Append-only journal file — pure java.io, runs
 * on the JVM against a temp dir.
 */
class StreamHeartbeatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `escape unescape roundtrip keeps backslash newline CR and unicode`() {
        val cases = listOf(
            "",
            "plain text",
            "line1\nline2",
            "back\\slash",
            "cr\rreturn",
            "mixed \n \\ \r end",
            "кириллица и эмодзи 🚀\nвторя строка\\",
            "\n\n\n",
            "\\\\n",   // literal backslash-backslash-n
        )
        for (c in cases) {
            assertEquals(c, StreamHeartbeat.unescape(StreamHeartbeat.escape(c)))
        }
    }

    @Test
    fun `escaped line contains no raw newline`() {
        val escaped = StreamHeartbeat.escape("a\nb\rc\\d")
        assertTrue(escaped.indexOf('\n') < 0)
        assertTrue(escaped.indexOf('\r') < 0)
    }

    @Test
    fun `append and read back concatenated deltas`() {
        val dir = tmp.newFolder()
        StreamHeartbeat.appendDelta(dir, "s1", "Hello ")
        StreamHeartbeat.appendDelta(dir, "s1", "world\n")
        StreamHeartbeat.appendDelta(dir, "s1", "кириллица")
        assertEquals("Hello world\nкириллица", StreamHeartbeat.readText(dir, "s1"))
    }

    @Test
    fun `empty delta is a no-op`() {
        val dir = tmp.newFolder()
        StreamHeartbeat.appendDelta(dir, "s1", "")
        assertNull(StreamHeartbeat.readText(dir, "s1"))
    }

    @Test
    fun `truncated final line is dropped not misparsed`() {
        val dir = tmp.newFolder()
        StreamHeartbeat.appendDelta(dir, "s1", "complete line\n")
        StreamHeartbeat.appendDelta(dir, "s1", "partial with no terminator")
        // Simulate a process death cutting the write: strip the trailing \n.
        val f = dir.resolve("s1" + StreamHeartbeat.FILE_SUFFIX)
        val raw = f.readText()
        assertTrue(raw.endsWith("\n"))
        f.writeText(raw.trimEnd('\n'))
        assertEquals("complete line\n", StreamHeartbeat.readText(dir, "s1"))
    }

    @Test
    fun `delete removes the journal`() {
        val dir = tmp.newFolder()
        StreamHeartbeat.appendDelta(dir, "s1", "data")
        StreamHeartbeat.delete(dir, "s1")
        assertNull(StreamHeartbeat.readText(dir, "s1"))
        StreamHeartbeat.delete(dir, "s1") // idempotent
    }

    @Test
    fun `recoverOrphans returns oldest first and cleans up`() {
        val dir = tmp.newFolder()
        val a = dir.resolve("aaa" + StreamHeartbeat.FILE_SUFFIX)
        val b = dir.resolve("bbb" + StreamHeartbeat.FILE_SUFFIX)
        // b written first (older mtime), a second — recovery order must be b, a.
        b.writeText(StreamHeartbeat.escape("first stream text ") + "\n")
        Thread.sleep(5)
        a.writeText(StreamHeartbeat.escape("second stream text ") + "\n")
        val orphans = StreamHeartbeat.recoverOrphans(dir, minChars = 1)
        assertEquals(2, orphans.size)
        assertEquals("bbb", orphans[0].streamId)
        assertEquals("first stream text ", orphans[0].text)
        assertEquals("second stream text ", orphans[1].text)
        // Files consumed.
        assertTrue(!a.exists() && !b.exists())
        // Second call: nothing left.
        assertTrue(StreamHeartbeat.recoverOrphans(dir).isEmpty())
    }

    @Test
    fun `sub-threshold fragments are deleted without recovery`() {
        val dir = tmp.newFolder()
        val f = dir.resolve("tiny" + StreamHeartbeat.FILE_SUFFIX)
        f.writeText(StreamHeartbeat.escape("tiny race fragment") + "\n")
        val orphans = StreamHeartbeat.recoverOrphans(dir, minChars = StreamDurability.FIRST_WRITE_CHARS)
        assertTrue(orphans.isEmpty())
        assertTrue(!f.exists())
    }

    @Test
    fun `missing dir recovers nothing`() {
        assertTrue(StreamHeartbeat.recoverOrphans(tmp.newFolder().resolve("nope")).isEmpty())
    }

    @Test
    fun `deleteAll wipes the whole journal dir`() {
        val dir = tmp.newFolder()
        StreamHeartbeat.appendDelta(dir, "s1", "x")
        StreamHeartbeat.appendDelta(dir, "s2", "y")
        StreamHeartbeat.deleteAll(dir)
        assertNull(StreamHeartbeat.readText(dir, "s1"))
        assertNull(StreamHeartbeat.readText(dir, "s2"))
        assertTrue(!dir.exists())
    }
}
