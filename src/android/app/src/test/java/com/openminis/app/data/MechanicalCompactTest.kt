package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MechanicalCompactTest {

    private fun u(t: String) = MechanicalCompact.Turn(isUser = true, text = t)
    private fun a(t: String) = MechanicalCompact.Turn(isUser = false, text = t)

    @Test
    fun `empty range yields blank digest`() {
        assertEquals("", MechanicalCompact.buildDigest(emptyList()))
        assertEquals("", MechanicalCompact.buildDigest(listOf(u("   "), a("   "))))
    }

    @Test
    fun `user messages preserved verbatim and in newest-first order`() {
        val turns = listOf(u("first task"), a("reply"), u("second task"))
        val d = MechanicalCompact.buildDigest(turns)
        assertTrue(d.contains("first task"))
        assertTrue(d.contains("second task"))
        // Newest first: "second task" must appear before "first task".
        assertTrue(d.indexOf("second task") < d.indexOf("first task"))
    }

    @Test
    fun `last assistant message acts as where-we-stopped anchor`() {
        val turns = listOf(u("goal"), a("early"), a("last state: commit abc123"))
        val d = MechanicalCompact.buildDigest(turns)
        assertTrue(d.contains("last state: commit abc123"))
        assertFalse(d.contains("early"))  // only the LAST assistant turn
    }

    @Test
    fun `digest never exceeds its budget`() {
        val turns = (1..100).map { u("user turn #$it ${"x".repeat(500)}") }
        val d = MechanicalCompact.buildDigest(turns)
        // Cap + header + skipped-note; assert it stays well within a small window.
        assertTrue(d.length < 6600)
        // Oldest turned dropped once budget is exhausted — note is present.
        assertTrue(d.contains("опущено"))
        // Newest turn always survives.
        assertTrue(d.contains("user turn #100"))
    }

    // [T-tail-token-budget] The digest budget must scale with the ACTIVE
    // model's window — a flat digest can overflow the small-context model
    // it is rescuing (the exact 400 it exists to escape).
    @Test
    fun `digest char budget scales with the model window`() {
        assertEquals(2_000, MechanicalCompact.digestCharBudget(4_096))    // floor
        assertEquals(3_000, MechanicalCompact.digestCharBudget(8_000))
        assertEquals(6_000, MechanicalCompact.digestCharBudget(16_000))   // legacy default
        assertEquals(12_000, MechanicalCompact.digestCharBudget(32_000))
        assertEquals(24_000, MechanicalCompact.digestCharBudget(128_000)) // ceiling
        assertEquals(24_000, MechanicalCompact.digestCharBudget(1_000_000))
    }

    @Test
    fun `small window gets a proportionally smaller digest`() {
        // Same input, two windows: an 8k window must produce a digest that
        // fits ITS budget (3000 chars), while a 128k window may keep more.
        val turns = (1..60).map { u("требование #$it ${"данные".repeat(40)}") }
        val small = MechanicalCompact.buildDigest(turns, charBudget = MechanicalCompact.digestCharBudget(8_000))
        val large = MechanicalCompact.buildDigest(turns, charBudget = MechanicalCompact.digestCharBudget(128_000))
        assertTrue(small.length <= 3_000)
        assertTrue(large.length > small.length)
        // Budget integrity: the small digest dropped older turns visibly.
        assertTrue(small.contains("опущено"))
    }

    @Test
    fun `buildDigest default matches the legacy 6000-char cap`() {
        // No-arg call keeps the historical behaviour (16k-tier budget) —
        // existing callers that don't know the window are unaffected.
        val turns = (1..100).map { u("user turn #$it ${"x".repeat(500)}") }
        val d = MechanicalCompact.buildDigest(turns)
        assertTrue(d.length < 6600)
    }

    @Test
    fun `whole digest provably fits its char budget`() {
        // The header, skipped note and assistant anchor are all paid for
        // out of the SAME budget — the digest must never exceed the cap it
        // was given, on any window tier (pinned after the old version
        // appended ~1.5k of trailing blocks on top of the cap).
        val withAssistant = (1..200).map { u("task $it") } + listOf(a("x".repeat(5000)))
        for (window in intArrayOf(4_096, 8_000, 16_000, 32_000, 128_000, 1_000_000)) {
            val budget = MechanicalCompact.digestCharBudget(window)
            val d = MechanicalCompact.buildDigest(withAssistant, charBudget = budget)
            assertTrue("window=$window budget=$budget len=${d.length}", d.length <= budget)
        }
    }

    @Test
    fun `assistant-only range still digests`() {
        val d = MechanicalCompact.buildDigest(listOf(a("final state")))
        assertTrue(d.contains("final state"))
    }
}
