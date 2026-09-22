package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-auto-mode-budget-challenge] «выдал 2 часа = работай все 2 часа»: a
 * completion claimed with budget left gets a bounded challenge; the model
 * wins by repeating TASK_COMPLETE in its reply.
 */
class AutoModeBudgetChallengeTest {

    @Test
    fun `gate challenges while budget is fat and challenges remain`() {
        assertTrue(AutoModeBudgetChallenge.shouldChallenge(60L * 60 * 1000, 0))
        assertTrue(AutoModeBudgetChallenge.shouldChallenge(20L * 60 * 1000, 1))
    }

    @Test
    fun `gate accepts completion when budget nearly spent`() {
        assertFalse(AutoModeBudgetChallenge.shouldChallenge(10L * 60 * 1000, 0))
        assertFalse(AutoModeBudgetChallenge.shouldChallenge(0, 0))
    }

    @Test
    fun `gate accepts when challenges exhausted - model insistence wins`() {
        assertFalse(AutoModeBudgetChallenge.shouldChallenge(60L * 60 * 1000, 2))
        assertFalse(AutoModeBudgetChallenge.shouldChallenge(60L * 60 * 1000, 5))
    }

    @Test
    fun `gate accepts when no timer armed`() {
        // -1 = no turn deadline — nothing to honor.
        assertFalse(AutoModeBudgetChallenge.shouldChallenge(-1L, 0))
    }

    @Test
    fun `threshold boundary - exactly 15 minutes is not challenged`() {
        assertFalse(AutoModeBudgetChallenge.shouldChallenge(AutoModeBudgetChallenge.CHALLENGE_THRESHOLD_MS, 0))
        assertTrue(
            AutoModeBudgetChallenge.shouldChallenge(AutoModeBudgetChallenge.CHALLENGE_THRESHOLD_MS + 1, 0),
        )
    }

    @Test
    fun `prompt carries the mandate and the out`() {
        val text = AutoModeBudgetChallenge.prompt(90L * 60 * 1000, 1)
        assertTrue(text.contains("⟳ Авто-режим · проверка завершения #1"))
        // The mandate: budget left, keep improving.
        assertTrue(text.contains("осталось 90 мин"))
        assertTrue(text.contains("НАИЛУЧШИМ"))
        // The out: repeating the sentinel ends the run, no questions.
        assertTrue(text.contains("повтори TASK_COMPLETE"))
        // The continuation must not be disarm-safe by accident: it must NOT
        // contain a bare TASK_COMPLETE line that would arm-disarm the loop.
        assertFalse(text.trim().endsWith("TASK_COMPLETE"))
    }

    @Test
    fun `prompt minute formatting rounds down`() {
        val text = AutoModeBudgetChallenge.prompt(59_999L, 1) // < 1 minute
        assertTrue(text.contains("осталось 0 мин"))
    }
}
