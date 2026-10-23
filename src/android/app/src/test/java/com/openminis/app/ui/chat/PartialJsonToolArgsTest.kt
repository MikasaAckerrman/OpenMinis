package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [T-gc-churn] Guards the extraction out of ChatViewModel.
 *
 * The cases pin the SEMANTICS the previous private implementation had (copy
 * the remainder, scan from index 0, return the prefix) so the optimisation is
 * provably behaviour-preserving, plus the streaming shapes that actually
 * occur: a value still arriving mid-token, escaped quotes inside the value,
 * and a value that never closes.
 *
 * Built from ordinary escaped strings (not raw strings) so every literal is
 * unambiguous about where the backslashes land.
 */
class PartialJsonToolArgsTest {

    private fun json(inner: String): String = "{$inner"

    @Test
    fun extractsSpacedKey() {
        val s = json("\"tool_title\": \"Build the APK\"")
        assertEquals("Build the APK", PartialJsonToolArgs.extract("tool_title", s))
    }

    @Test
    fun extractsCompactKey() {
        val s = json("\"tool_title\":\"Build the APK\"")
        assertEquals("Build the APK", PartialJsonToolArgs.extract("tool_title", s))
    }

    @Test
    fun returnsNullWhenKeyHasNotStreamedYet() {
        assertNull(PartialJsonToolArgs.extract("tool_title", json("\"file_path\": \"/sdcard/\"")))
        assertNull(PartialJsonToolArgs.extract("tool_title", ""))
    }

    @Test
    fun stopsAtTheFirstUnescapedQuote() {
        val s = json("\"tool_title\": \"short\", \"x\": 1")
        assertEquals("short", PartialJsonToolArgs.extract("tool_title", s))
    }

    @Test
    fun keepsEscapedQuotesInsideTheValue() {
        // The whole point of findUnescapedEnd: an escaped quote must not
        // terminate the value.
        val s = json("\"tool_title\": \"say \\\"hi\\\" now\", \"rest\": 1")
        assertEquals("say \"hi\" now", PartialJsonToolArgs.extract("tool_title", s))
    }

    @Test
    fun unescapesCommonJsonEscapes() {
        val nl = json("\"tool_title\": \"line1\\nline2\"")
        assertEquals("line1\nline2", PartialJsonToolArgs.extract("tool_title", nl))

        val tab = json("\"tool_title\": \"a\\tb\"")
        assertEquals("a\tb", PartialJsonToolArgs.extract("tool_title", tab))

        val backslash = json("\"tool_title\": \"back\\\\slash\"")
        assertEquals("back\\slash", PartialJsonToolArgs.extract("tool_title", backslash))
    }

    @Test
    fun returnsRestOfDocumentWhenValueNeverCloses() {
        // Streaming case: the closing quote has not arrived yet.
        val s = json("\"tool_title\": \"still streaming")
        assertEquals("still streaming", PartialJsonToolArgs.extract("tool_title", s))
    }

    @Test
    fun handlesUnterminatedBackslashWithoutOverrun() {
        // A lone trailing backslash pushes the scan index past the end; it must
        // stop cleanly rather than read out of bounds.
        val s = "{\"tool_title\": \"abc\\"
        val v = PartialJsonToolArgs.extract("tool_title", s)
        assertEquals("abc\\", v)
    }

    @Test
    fun valueWithoutEscapesReturnsTheOriginalInstance() {
        // The fast path added to unescape(): no allocation when there is
        // nothing to unescape.
        assertSame("plain value", PartialJsonToolArgs.extract("tool_title", json("\"tool_title\": \"plain value\"")))
    }

    @Test
    fun keyIsFoundAmongOtherFields() {
        val s = json("\"id\":\"t1\",\"file_path\":\"/sdcard/x\",\"tool_title\": \"Write file\"")
        assertEquals("Write file", PartialJsonToolArgs.extract("tool_title", s))
    }

    @Test
    fun patternCacheDoesNotLeakBetweenKeys() {
        // patternsFor() caches by key; alternating keys must still resolve.
        val a = json("\"tool_title\": \"alpha\"")
        val b = json("\"tool_name\": \"beta\"")
        assertEquals("alpha", PartialJsonToolArgs.extract("tool_title", a))
        assertEquals("beta", PartialJsonToolArgs.extract("tool_name", b))
        assertEquals("alpha", PartialJsonToolArgs.extract("tool_title", a))
        assertNull(PartialJsonToolArgs.extract("tool_name", a))
    }

    @Test
    fun costIsBoundedByTheValueNotTheDocument() {
        // The regression this change exists for: reading one short value out
        // of a large document. The old code copied the whole remainder of the
        // document (O(n)) on every call; now only the value is materialised.
        val filler = "x".repeat(200_000)
        val doc = "{\"file_path\": \"$filler\",\"tool_title\": \"short title\"}"

        repeat(5) { PartialJsonToolArgs.extract("tool_title", doc) } // warm up

        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()

        val iterations = 2000
        var sinkLen = 0
        repeat(iterations) {
            sinkLen += PartialJsonToolArgs.extract("tool_title", doc)!!.length
        }

        val perCall = (rt.totalMemory() - rt.freeMemory() - before).toDouble() / iterations
        assertEquals(11, sinkLen / iterations) // "short title".length

        // 200 KB document: the old code allocated ~200 KB per call here.
        // Generous headroom for JVM noise, but far below any document copy.
        assert(
            "allocated $perCall bytes per call - expected the value, not the document",
            perCall < 8_192,
        )
    }
}