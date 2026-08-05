package com.openminis.app.offload

import com.openminis.app.offload.AgentDispatcher.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The dispatcher's two pure decisions: how a classifier reply becomes a level,
 * and which graph a level picks.
 *
 * These are worth pinning because both failure modes are silent. A parse that
 * wrongly returns null degrades to normal chat, so auto-routing looks like it
 * "just doesn't work"; a parse that wrongly returns L3 spends seven model calls
 * on a typo fix. Neither shows up as an error anywhere.
 */
class AgentDispatcherTest {

    // ── parseLevel: shapes models actually emit ───────────────────────────────

    @Test
    fun `bare json object`() {
        assertEquals(Level.L2, AgentDispatcher.parseLevel("""{"level":"L2","why":"multi-step"}"""))
    }

    @Test
    fun `json in a fenced block`() {
        val reply = """
            ```json
            {"level": "L4", "why": "new subsystem"}
            ```
        """.trimIndent()
        assertEquals(Level.L4, AgentDispatcher.parseLevel(reply))
    }

    @Test
    fun `json wrapped in prose`() {
        val reply = "Sure — here is the classification:\n{\"level\":\"L0\",\"why\":\"question\"}\nHope that helps."
        assertEquals(Level.L0, AgentDispatcher.parseLevel(reply))
    }

    @Test
    fun `lowercase level value`() {
        assertEquals(Level.L3, AgentDispatcher.parseLevel("""{"level":"l3","why":"cross-cutting"}"""))
    }

    @Test
    fun `bare token without json`() {
        assertEquals(Level.L1, AgentDispatcher.parseLevel("L1"))
        assertEquals(Level.L2, AgentDispatcher.parseLevel("Level: L2 — one area"))
    }

    @Test
    fun `malformed json falls back to the token scan`() {
        // Truncated by maxTokens mid-object: the JSON parse fails, but the
        // level is still unambiguously present.
        assertEquals(Level.L3, AgentDispatcher.parseLevel("""{"level":"L3","why":"cross-cut"""))
    }

    @Test
    fun `unusable replies return null rather than a guess`() {
        assertNull(AgentDispatcher.parseLevel(null))
        assertNull(AgentDispatcher.parseLevel(""))
        assertNull(AgentDispatcher.parseLevel("   "))
        assertNull(AgentDispatcher.parseLevel("I cannot classify this request."))
        // Out of range — L5 is not a level, and must not round down to L4.
        assertNull(AgentDispatcher.parseLevel("""{"level":"L5","why":"huge"}"""))
        // A level-shaped substring inside a longer token is not a level.
        assertNull(AgentDispatcher.parseLevel("SHA L2FF"))
    }

    @Test
    fun `json level wins over an unrelated number elsewhere in the reply`() {
        val reply = """{"level":"L1","why":"single file"} (not L4)"""
        assertEquals(Level.L1, AgentDispatcher.parseLevel(reply))
    }

    // ── needsGraph: the escalation threshold ─────────────────────────────────

    @Test
    fun `only L2 and above deserve the graph`() {
        assertEquals(false, Level.L0.needsGraph)
        assertEquals(false, Level.L1.needsGraph)
        assertEquals(true, Level.L2.needsGraph)
        assertEquals(true, Level.L3.needsGraph)
        assertEquals(true, Level.L4.needsGraph)
    }

    // ── defaultGraphFor: cost of the choice ─────────────────────────────────

    @Test
    fun `L2 gets the three-agent graph and L3 plus the full chain`() {
        assertEquals(BuiltinGraphs.ID_LIGHT, AgentDispatcher.defaultGraphFor(Level.L2))
        assertEquals(BuiltinGraphs.ID_FULL, AgentDispatcher.defaultGraphFor(Level.L3))
        assertEquals(BuiltinGraphs.ID_FULL, AgentDispatcher.defaultGraphFor(Level.L4))
    }
}
