package com.openminis.app.ui.chat

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-live-turn-buffer] The live turn buffer is appended to by the streaming
 * loop and copied by a terminator running on another thread.
 *
 * The property that matters: a copy taken while the other thread is
 * appending is always a whole number of chunks, and never throws.
 *
 * The reason the property needed a class of its own: StringBuilder's
 * toString() is `new String(value, 0, count)`, and the two fields are read
 * without a lock. A reader can therefore see the new count against the old
 * array and get an ArrayIndexOutOfBoundsException - which the caller caught
 * and answered by falling back to a throttled snapshot, losing up to the
 * flush window of the user's text.
 */
class LiveTurnBufferTest {

    @Test
    fun `snapshot returns exactly what was appended, in order`() {
        val buf = LiveTurnBuffer()
        buf.append("hello ")
        buf.append("world")
        assertEquals("hello world", buf.snapshot())
    }

    @Test
    fun `clear drops everything`() {
        val buf = LiveTurnBuffer()
        buf.append("something")
        buf.clear()
        assertEquals("", buf.snapshot())
        assertEquals(0, buf.length)
    }

    @Test
    fun `length tracks the appended text`() {
        val buf = LiveTurnBuffer()
        assertEquals(0, buf.length)
        buf.append("12345")
        assertEquals(5, buf.length)
    }

    @Test
    fun `substring returns the requested slice`() {
        val buf = LiveTurnBuffer()
        buf.append("0123456789")
        assertEquals("234", buf.substring(2, 5))
    }

    /**
     * The invariant the terminator depends on: while another thread appends,
     * every copy is a whole number of chunks and never throws.
     */
    @Test
    fun `a copy taken while another thread appends is never torn`() {
        val buf = LiveTurnBuffer()
        val chunk = "x".repeat(4096)
        val stop = AtomicBoolean(false)
        val writer = thread(name = "live-turn-writer") {
            while (!stop.get()) buf.append(chunk)
        }
        try {
            repeat(2000) {
                val s = buf.snapshot()
                assertEquals(
                    "a snapshot must contain a whole number of chunks",
                    0,
                    s.length % chunk.length,
                )
                assertTrue(
                    "a snapshot must not contain characters from a partial chunk",
                    s.all { it == 'x' },
                )
            }
        } finally {
            stop.set(true)
            writer.join(5_000)
        }
    }
}
