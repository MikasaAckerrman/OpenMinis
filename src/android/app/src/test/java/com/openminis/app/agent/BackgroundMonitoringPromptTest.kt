package com.openminis.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-bg-monitoring] Guards [SystemPromptBuilder.BACKGROUND_MONITORING]
 * — the system-prompt contract (§4 AGENTS.md) instructing the agent on
 * how to handle long-running external processes without blocking turns.
 *
 * Verifies presence, non-triviality, attention budget, and essential
 * lifecycle rules (run_id, polling, limits, prohibitions).
 */
class BackgroundMonitoringPromptTest {

    private val text = SystemPromptBuilder.BACKGROUND_MONITORING

    @Test
    fun `is present and non-trivial`() {
        assertTrue("BACKGROUND_MONITORING must not be blank", text.isNotBlank())
        assertTrue("must contain header line", text.contains("Background monitoring"))
        assertTrue("must be within reasonable attention budget", text.length in 500..2500)
    }

    @Test
    fun `contains core lifecycle steps`() {
        val lower = text.lowercase()
        listOf("run_id", "status", "completed", "success", "failure", "cancelled")
            .forEach { step ->
                assertTrue("missing lifecycle step: $step", lower.contains(step))
            }
    }

    @Test
    fun `contains operational limits`() {
        val lower = text.lowercase()
        listOf("30 minutes", "rate limit", "github api", "5000 requests").forEach { limit ->
            assertTrue("missing operational limit: $limit", lower.contains(limit))
        }
    }

    @Test
    fun `contains strict prohibitions`() {
        val lower = text.lowercase()
        assertTrue("must forbid sleep", lower.contains("no sleep") || lower.contains("through sleep"))
        assertTrue("must forbid fixed timers", lower.contains("fixed timer"))
        assertTrue("must forbid blocking main loop", lower.contains("block the main loop"))
    }
}
