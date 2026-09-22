package com.openminis.app.offload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-proactive-memory] Pure policy of the post-turn memory distiller.
 *
 * The gate decides which turns are worth a distillation model call, and the
 * nudge cadence decides which tool results carry the mid-turn reminder —
 * both are money decisions, so they are pinned here.
 */
class TurnMemoryDistillerTest {

    private fun digest(assistantChars: Int = 0, toolMentions: Int = 0) =
        TurnMemoryDistiller.TurnDigest(
            userChars = 100,
            assistantChars = assistantChars,
            toolMentions = toolMentions,
        )

    @Test
    fun `a trivial turn is not distilled`() {
        // "спасибо" → "пожалуйста" — nothing durable, no model call.
        assertFalse(TurnMemoryDistiller.shouldDistill(digest(assistantChars = 120), memoryEnabled = true))
        assertFalse(TurnMemoryDistiller.shouldDistill(digest(toolMentions = 1), memoryEnabled = true))
    }

    @Test
    fun `a substantial answer is distilled`() {
        assertTrue(TurnMemoryDistiller.shouldDistill(digest(assistantChars = 1500), memoryEnabled = true))
        assertTrue(TurnMemoryDistiller.shouldDistill(digest(assistantChars = 4000), memoryEnabled = true))
    }

    @Test
    fun `a tool-heavy turn is distilled even when the answer is short`() {
        // The findings live in what the tools returned, not the prose.
        assertTrue(TurnMemoryDistiller.shouldDistill(digest(assistantChars = 80, toolMentions = 3), memoryEnabled = true))
    }

    @Test
    fun `memory disabled wins over everything`() {
        assertFalse(
            TurnMemoryDistiller.shouldDistill(
                digest(assistantChars = 5000, toolMentions = 9),
                memoryEnabled = false,
            ),
        )
    }

    @Test
    fun `nudge fires on every 10th tool call and only then`() {
        val fired = (1..24).count { TurnMemoryDistiller.shouldNudge(it) }
        assertTrue("expected nudges at 10 and 20, got $fired", fired == 2)
        assertFalse(TurnMemoryDistiller.shouldNudge(0))
        assertFalse(TurnMemoryDistiller.shouldNudge(9))
        assertTrue(TurnMemoryDistiller.shouldNudge(10))
        assertFalse(TurnMemoryDistiller.shouldNudge(11))
    }

    @Test
    fun `the nudge line tells the model exactly what to do`() {
        val line = TurnMemoryDistiller.nudgeLine()
        assertTrue(line.contains("memory_write"))
        assertTrue(line.contains("NOW"))
    }

    @Test
    fun `tool mention counter matches every tool block schema variant`() {
        // Gate heuristic, not a parser: "tool_use" / "toolCall" / "tool_result"
        // all count via the "type":"tool prefix.
        val parts = """[{"type":"tool_use","name":"shell"},{"type":"text","text":"hi"},{"type":"toolCall","name":"x"}]"""
        assertTrue(TurnMemoryDistiller.toolMentionsIn(parts) == 2)
        assertTrue(TurnMemoryDistiller.toolMentionsIn("""[{"type":"text","text":"hi"}]""") == 0)
        assertTrue(TurnMemoryDistiller.toolMentionsIn("") == 0)
    }
}
