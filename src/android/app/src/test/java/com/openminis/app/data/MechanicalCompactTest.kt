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

    @Test
    fun `assistant-only range still digests`() {
        val d = MechanicalCompact.buildDigest(listOf(a("final state")))
        assertTrue(d.contains("final state"))
    }
}
