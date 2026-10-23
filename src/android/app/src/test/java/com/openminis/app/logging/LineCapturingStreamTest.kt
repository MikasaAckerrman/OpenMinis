package com.openminis.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * [T-stdout-capture] Two defects in the stdout/stderr capture stream:
 *
 *  1. The buffer drains only on '\n', so a writer that emits no newline grew
 *     it without bound — and ByteArrayOutputStream doubles its backing array on
 *     every overflow, so newline-free child output was an OutOfMemoryError.
 *  2. There was no `close()` at all while the KDoc promised "Partial lines
 *     are flushed on close()", so a program ending without a trailing newline
 *     lost that line for good.
 */
class LineCapturingStreamTest {

    private val lines = mutableListOf<String>()

    private fun stream(): AppLogger.LineCapturingStream =
        AppLogger.LineCapturingStream(
            PrintStream(ByteArrayOutputStream(), true),
            "STDOUT",
        ) { lines.add(it) }

    @Test
    fun newlineTerminatedLinesAreEmittedAsTheyArrive() {
        val s = stream()
        s.write("first\nsecond\n".toByteArray())
        assertEquals(listOf("first", "second"), lines)
    }

    @Test
    fun partialLineIsHeldUntilTheNewlineArrives() {
        val s = stream()
        s.write("no newline yet".toByteArray())
        // Still buffered: emitting here would split every real line in two.
        assertTrue(lines.isEmpty())
        s.write(" and now it ends\n".toByteArray())
        assertEquals(listOf("no newline yet and now it ends"), lines)
    }

    @Test
    fun closeFlushesTheTrailingPartialLine() {
        val s = stream()
        s.write("ends without a newline".toByteArray())
        assertTrue("nothing emitted before close", lines.isEmpty())
        s.close()
        assertEquals(listOf("ends without a newline"), lines)
    }

    @Test
    fun unterminatedOutputIsTruncatedAndTheTruncationIsReported() {
        val s = stream()
        // 1 MB with no newline: the exact shape that used to OOM.
        val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
        repeat(16) { s.write(chunk, 0, chunk.size) }
        s.write('\n'.code)

        assertEquals("one captured line", 1, lines.size)
        val line = lines.single()
        assertTrue(
            "truncation must be visible, not silent: $line",
            line.contains("[line truncated at "),
        )
        assertTrue(
            "the cap must be far below the 1 MB written, got ${line.length} chars",
            line.length < 100 * 1024,
        )
    }

    @Test
    fun truncationMarkerIsReportedOncePerLine() {
        val s = stream()
        val chunk = ByteArray(64 * 1024) { 'y'.code.toByte() }
        repeat(4) { s.write(chunk, 0, chunk.size) }
        s.write('\n'.code)
        // The overflow flag must not leak into the following line.
        s.write("clean\n".toByteArray())
        assertEquals(2, lines.size)
        assertEquals("clean", lines[1])
    }

    @Test
    fun singleByteWritesAlsoRespectTheCap() {
        val s = stream()
        repeat(200_000) { s.write('z'.code) }   // 200 KB, one byte at a time
        s.write('\n'.code)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("[line truncated at "))
    }
}