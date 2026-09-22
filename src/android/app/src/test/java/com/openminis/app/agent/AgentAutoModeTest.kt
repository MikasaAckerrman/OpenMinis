package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-auto-mode] The autonomous loop's contract, proven pure:
 * arming phrases, completion sentinel detection, budget gate, prompt text.
 */
class AgentAutoModeTest {

    // ── arming ─────────────────────────────────────────────────────────────

    @Test
    fun `user phrases arm the run`() {
        assertTrue(AgentAutoMode.wantsAutoMode("выполняй план в авто-режиме"))
        assertTrue(AgentAutoMode.wantsAutoMode("включи Авто Режим и делай"))
        assertTrue(AgentAutoMode.wantsAutoMode("run auto mode until done"))
        assertTrue(AgentAutoMode.wantsAutoMode("/auto"))
    }

    @Test
    fun `plain requests do not arm`() {
        assertFalse(AgentAutoMode.wantsAutoMode("сделай всё сам"))
        assertFalse(AgentAutoMode.wantsAutoMode("автоматически")) // substring? no — full phrase required
        assertFalse(AgentAutoMode.wantsAutoMode(""))
    }

    // ── completion ─────────────────────────────────────────────────────────

    @Test
    fun `sentinel alone on a line completes`() {
        val text = "Итог: всё сделано, CI зелёный.\n\nTASK_COMPLETE"
        assertTrue(AgentAutoMode.isPlanComplete(text))
        assertTrue(AgentAutoMode.isPlanComplete("TASK_COMPLETE"))
        assertTrue(AgentAutoMode.isPlanComplete("  task_complete  "))
    }

    @Test
    fun `a mere mention does not complete`() {
        // Inline mention — not its own line.
        assertFalse(AgentAutoMode.isPlanComplete("Остановлюсь, когда напишу TASK_COMPLETE в конце."))
        // Not the sentinel at all.
        assertFalse(AgentAutoMode.isPlanComplete("Готово."))
        assertFalse(AgentAutoMode.isPlanComplete(""))
    }

    // ── the gate ───────────────────────────────────────────────────────────

    @Test
    fun `gate continues while armed with budget and no completion`() {
        assertTrue(
            AgentAutoMode.shouldContinue(
                armed = true, turnsUsed = 0,
                lastAssistantText = "работаю", contextFull = false,
            ),
        )
    }

    @Test
    fun `gate stops on completion budget or disarm`() {
        assertFalse(
            AgentAutoMode.shouldContinue(
                armed = true, turnsUsed = 0,
                lastAssistantText = "done\nTASK_COMPLETE", contextFull = false,
            ),
        )
        assertFalse(
            AgentAutoMode.shouldContinue(
                armed = true, turnsUsed = AgentAutoMode.MAX_AUTO_TURNS,
                lastAssistantText = "still going", contextFull = false,
            ),
        )
        assertFalse(
            AgentAutoMode.shouldContinue(
                armed = false, turnsUsed = 0,
                lastAssistantText = "still going", contextFull = false,
            ),
        )
    }

    @Test
    fun `gate pauses when context is full (compact before continue)`() {
        assertFalse(
            AgentAutoMode.shouldContinue(
                armed = true, turnsUsed = 5,
                lastAssistantText = "работаю", contextFull = true,
            ),
        )
    }

    // ── the prompt ─────────────────────────────────────────────────────────

    @Test
    fun `continuation 1 carries the full contract`() {
        val p = AgentAutoMode.continuationPrompt(1)
        assertTrue(p.contains("1"))
        assertTrue(p.contains(AgentAutoMode.SENTINEL))
        assertTrue(p.contains("чекпоинт"))
        assertTrue(p.contains("наилучш"))
        // [T-auto-mode-verify] The machine half: every work turn must end
        // with a VERIFY block the engine executes itself.
        assertTrue(p.contains("VERIFY:"))
        assertTrue(p.contains("менеджер проверяет"))
    }

    @Test
    fun `later continuations use the short prompt - token economics`() {
        val full = AgentAutoMode.continuationPrompt(1)
        val short = AgentAutoMode.continuationPrompt(2)
        // Every rule keyword survives the compression…
        assertTrue(short.contains(AgentAutoMode.SENTINEL))
        assertTrue(short.contains("VERIFY"))
        assertTrue(short.contains("чекпоинт"))
        assertTrue(short.contains("наилучш"))
        assertTrue(short.contains("2"))
        // …at a fraction of the size (500 turns would otherwise re-send
        // ~750 KB of duplicated contract text).
        assertTrue(short.length < full.length / 3)
    }

    @Test
    fun `replan prompt demands root cause and a different strategy`() {
        val p = AgentAutoMode.replanPrompt(1, listOf("файл не существует: /a.kt"))
        assertTrue(p.contains("REPLAN #1"))
        assertTrue(p.contains("ПЕРВОПРИЧИНУ"))
        assertTrue(p.contains("другую стратегию") || p.contains("ДРУГУЮ стратегию"))
        assertTrue(p.contains("/a.kt"))
    }
}
