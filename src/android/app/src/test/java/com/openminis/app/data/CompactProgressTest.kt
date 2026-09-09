package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-progress] Pure logic of the compact progress card: reporter
 * aggregation, percent math, elapsed/token formatting, proactive-split
 * decision and failure summaries.
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
    fun `proactive split fires when transcript cannot fit the window`() {
        // 8k window: 60% = 4800 tokens = 19200 chars.
        assertTrue(CompactChunking.shouldSplitProactively(20_000, 8_000))
        assertEquals(false, CompactChunking.shouldSplitProactively(10_000, 8_000))
        // 128k window needs a huge transcript.
        assertTrue(CompactChunking.shouldSplitProactively(400_000, 128_000))
        assertEquals(false, CompactChunking.shouldSplitProactively(50_000, 128_000))
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
