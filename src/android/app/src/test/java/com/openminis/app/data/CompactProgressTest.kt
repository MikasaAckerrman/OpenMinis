package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-progress] Pure logic of the compact progress card: reporter
 * aggregation, percent math, elapsed/token formatting, window-packing
 * chunking, not-size error exemptions and failure summaries.
 */
class CompactProgressTest {

    // ── CompactMath ────────────────────────────────────────────────────────

    @Test
    fun `formatElapsed formats m-ss`() {
        assertEquals("0:00", CompactMath.formatElapsed(0))
        assertEquals("0:07", CompactMath.formatElapsed(7_000))
        assertEquals("1:05", CompactMath.formatElapsed(65_000))
        assertEquals("12:05", CompactMath.formatElapsed(725_000))
        assertEquals("0:00", CompactMath.formatElapsed(-5))   // negative clamps
    }

    @Test
    fun `formatTokens formats k with one decimal under 10k`() {
        assertEquals("900", CompactMath.formatTokens(900))
        assertEquals("1.2k", CompactMath.formatTokens(1_234))
        assertEquals("12k", CompactMath.formatTokens(12_345))
        assertEquals("123k", CompactMath.formatTokens(123_456))
    }

    // ── CompactRunReporter ─────────────────────────────────────────────────

    @Test
    fun `reporter single call percent follows chars toward cap 99`() {
        val r = CompactRunReporter(startMs = 0)
        r.phase(CompactPhase.SUMMARIZING)
        r.callStart("mini", chunkIndex = 1, chunkCount = 1)
        r.callChars(1, 0, 1_000)
        assertEquals(0, r.snapshot().percent)
        r.callChars(1, 500, 1_000)
        assertEquals(50, r.snapshot().percent)
        r.callChars(1, 5_000, 1_000)             // overshoot clamps at 0.99
        assertEquals(99, r.snapshot().percent)
        assertEquals(CompactPhase.SUMMARIZING, r.snapshot().phase)
        assertEquals("mini", r.snapshot().modelLabel)
    }

    @Test
    fun `reporter two parallel halves average their fractions`() {
        val r = CompactRunReporter(startMs = 0)
        r.phase(CompactPhase.SUMMARIZING)
        // Both halves announce topology 2 before streaming.
        r.callStart("a", 1, 2)
        r.callStart("b", 2, 2)
        r.callChars(1, 1_000, 1_000)             // half 1 done-ish (0.99)
        r.callChars(2, 500, 1_000)               // half 2 at 0.5
        assertEquals(74, r.snapshot().percent)   // (0.99+0.5)/2 = 0.745 → 74
    }

    @Test
    fun `reporter topology change resets fractions`() {
        val r = CompactRunReporter(startMs = 0)
        r.callStart("a", 1, 2)
        r.callStart("b", 2, 2)
        r.callChars(2, 1_000, 1_000)
        // Merge is a fresh 1/1 call — bar restarts, not stuck at old fraction.
        r.callStart("merge", 1, 1)
        assertEquals(0, r.snapshot().percent)
        assertEquals(1, r.snapshot().chunkCount)
    }

    @Test
    fun `reporter done sets 100 and note is transient`() {
        val r = CompactRunReporter(startMs = 0)
        r.note("Переключаюсь на X")
        assertEquals("Переключаюсь на X", r.snapshot().routeNote)
        r.done()
        assertEquals(100, r.snapshot().percent)
        assertEquals(CompactPhase.DONE, r.snapshot().phase)
    }

    @Test
    fun `reporter emits immutable snapshots through onUpdate`() {
        val seen = mutableListOf<CompactProgress>()
        val r = CompactRunReporter(startMs = 42) { seen.add(it) }
        r.phase(CompactPhase.PREPARING)
        r.phase(CompactPhase.SUMMARIZING)
        assertEquals(2, seen.size)               // phase() publishes each time
        assertEquals(42, seen.last().startMs)
        assertNull(seen.last().failure)
    }

    // ── CompactChunking ────────────────────────────────────────────────────

    @Test
    fun `per-call cap is window-bound and relay-bound`() {
        // Small window: 2 chars per token.
        assertEquals(16_000, CompactChunking.perCallInputCapChars(8_000))
        assertEquals(2_000, CompactChunking.perCallInputCapChars(1_000))
        // Big window: relay body cap wins.
        assertEquals(CompactChunking.MAX_RELAY_BODY_CHARS, CompactChunking.perCallInputCapChars(128_000))
    }

    @Test
    fun `packWindows packs along line boundaries under the cap`() {
        val text = (1..300).joinToString("\n") { "line $it with some padding text to add weight" }
        val cap = 1_000
        val windows = CompactChunking.packWindows(text, cap)
        assertTrue(windows.size > 1)
        windows.forEach { w -> assertTrue("window ${w.length} > cap", w.length <= cap) }
        // No content lost: every non-empty line survives in some window.
        val joined = windows.joinToString("\n")
        assertTrue(joined.contains("line 1 "))
        assertTrue(joined.contains("line 300 "))
    }

    @Test
    fun `packWindows splits a single oversized line by characters`() {
        // One 5k-char line with a 1k cap must still produce fitting windows.
        val text = "x".repeat(5_000)
        val windows = CompactChunking.packWindows(text, 1_000)
        assertEquals(5, windows.size)
        windows.forEach { w -> assertEquals(1_000, w.length) }
    }

    @Test
    fun `packWindows drops blank input and blank windows`() {
        assertTrue(CompactChunking.packWindows("", 1_000).isEmpty())
        assertTrue(CompactChunking.packWindows("   \n  \n", 1_000).isEmpty())
    }

    // ── TransportErrorClassifier: not-size exemptions ──────────────────────

    @Test
    fun `auth and quota errors are definitely not size related`() {
        assertTrue(TransportErrorClassifier.isDefinitelyNotSizeRelated("401 unauthorized"))
        assertTrue(TransportErrorClassifier.isDefinitelyNotSizeRelated("429 rate limit exceeded"))
        assertTrue(TransportErrorClassifier.isDefinitelyNotSizeRelated("insufficient quota"))
        assertTrue(TransportErrorClassifier.isDefinitelyNotSizeRelated("Недостаточно средств на балансе"))
        // Size-ish and gateway-worded errors stay halvable.
        assertFalse(TransportErrorClassifier.isDefinitelyNotSizeRelated("запрос отклонен шлюзом"))
        assertFalse(TransportErrorClassifier.isDefinitelyNotSizeRelated("context length exceeded"))
    }

    // ── CompactFailure ─────────────────────────────────────────────────────

    @Test
    fun `failure summary names every model attempt`() {
        val f = CompactFailure(
            attempts = listOf(
                CompactRouteAttempt("gpt-5-mini", "401 Invalid API key"),
                CompactRouteAttempt("gemini-flash", "429 rate limit"),
            ),
            terminal = "unused",
        )
        assertEquals("gpt-5-mini: 401 Invalid API key · gemini-flash: 429 rate limit", f.summary)
    }

    @Test
    fun `failure summary falls back to terminal when no attempts`() {
        val f = CompactFailure(attempts = emptyList(), terminal = "якорь потерян")
        assertEquals("якорь потерян", f.summary)
    }
}
