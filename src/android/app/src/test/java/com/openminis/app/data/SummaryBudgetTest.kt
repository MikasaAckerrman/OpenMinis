package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-summary-budget] The compact summary rides in EVERY request after a
 * compaction — it must be sized against the READER's window, not left at
 * whatever length the summariser happened to produce.
 */
class SummaryBudgetTest {

    @Test
    fun `token budget is one eighth of the window`() {
        assertEquals(512, SummaryBudget.maxTokens(4_096))     // floor
        assertEquals(1_000, SummaryBudget.maxTokens(8_000))
        assertEquals(2_000, SummaryBudget.maxTokens(16_000))
        assertEquals(4_000, SummaryBudget.maxTokens(32_000))
        assertEquals(8_192, SummaryBudget.maxTokens(128_000)) // cap (128k/8=16k → 8192)
        assertEquals(8_192, SummaryBudget.maxTokens(1_000_000))
    }

    @Test
    fun `char budget uses a conservative 4 chars per token`() {
        assertEquals(4_000, SummaryBudget.maxChars(8_000))
        assertEquals(16_000, SummaryBudget.maxChars(32_000))
        assertEquals(32_768, SummaryBudget.maxChars(128_000))
    }

    @Test
    fun `summary within budget is returned unchanged`() {
        val s = "line one\nline two\nline three"
        assertEquals(s, SummaryBudget.clamp(s, 128_000))
    }

    @Test
    fun `oversize summary is clamped with a visible note`() {
        // 8k window → 32_000 char budget. Build a summary that overruns it.
        val line = "факт #" + "x".repeat(78) + "\n"   // 80 chars per line
        val droppedRef = "/var/minis/shared/openminis/STATE.md"
        val summary = line.repeat(300) +              // 24_000 chars (kept head)
            "middle filler\n".repeat(500) +           // 7_500 chars (dropped)
            "final: $droppedRef\n"                    // ref in the DROPPED tail
        val clamped = SummaryBudget.clamp(summary, 8_000)
        assertTrue(clamped.length < summary.length)
        // Head survives.
        assertTrue(clamped.contains("факт #"))
        // The cut is visible.
        assertTrue(clamped.contains("обрезана"))
        // The full summary text is not silently lost — note says where it lives.
        assertTrue(clamped.contains("истории сессии"))
    }

    @Test
    fun `critical references from the dropped part survive the clamp`() {
        val droppedRef = "https://github.com/MikasaAckerrman/OpenMinis/pull/42"
        val pathRef = "/var/minis/shared/apk-builds/app-clone.zip"
        val summary = ("filler line\n" + "y".repeat(90) + "\n").repeat(900) +
            "links: $droppedRef and $pathRef\n"
        val clamped = SummaryBudget.clamp(summary, 8_000)
        assertTrue(clamped.contains(droppedRef))
        assertTrue(clamped.contains(pathRef))
        // Refs come from the DROPPED part only — nothing that is already in
        // the head is duplicated.
        val headOnly = SummaryBudget.clamp(
            "already has $pathRef here\n" + ("z".repeat(95) + "\n").repeat(900),
            8_000,
        )
        assertEquals(1, Regex(Regex.escape(pathRef)).findAll(headOnly).count())
    }

    @Test
    fun `clamp result always fits the char budget plus bounded extras`() {
        // Note + refs add a bounded tail (refs capped at 8, note is fixed
        // format) — the result must stay within budget + slack, never the
        // full original size.
        val summary = ("big line\n" + "w".repeat(91) + "\n").repeat(2000)
        val clamped = SummaryBudget.clamp(summary, 8_000)
        val budget = SummaryBudget.maxChars(8_000)
        // head (≤ budget) + note (~90 chars) + refs (≤ 8 × ~120 chars).
        assertTrue(clamped.length <= budget + 90 + 8 * 130)
    }

    @Test
    fun `degenerate summary with no line breaks still clamps`() {
        // One enormous unbroken line: lastIndexOf('\n') finds nothing usable
        // → hard cut at the char budget, no crash, note appended.
        val summary = "q".repeat(100_000)
        val clamped = SummaryBudget.clamp(summary, 8_000)
        assertTrue(clamped.length < 100_000)
        assertTrue(clamped.contains("обрезана"))
    }
}
