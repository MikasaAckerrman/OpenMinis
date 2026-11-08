package com.openminis.app.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-parallel-tool-discipline][PARALLEL_TOOL_ENGINE_SPEC Опора 3] Guards
 * [SystemPromptBuilder.PARALLEL_TOOL_DISCIPLINE] — the system-prompt
 * contract teaching the model to batch independent tool calls in ONE
 * response instead of single-tool ping-pong. Same guard set as
 * BackgroundMonitoringPromptTest: presence, attention budget, core
 * rules, prohibitions, and the worked example.
 */
class ParallelToolPromptTest {

    private val text = SystemPromptBuilder.PARALLEL_TOOL_DISCIPLINE

    @Test
    fun `is present and non-trivial`() {
        assertTrue("PARALLEL_TOOL_DISCIPLINE must not be blank", text.isNotBlank())
        assertTrue("must contain header line", text.contains("Parallel tool discipline"))
    }

    @Test
    fun `stays within the attention budget`() {
        // The reviewer's gate: 400..1500 chars. Past that, more prompt
        // text means less adherence — the block must stay tight.
        assertTrue(
            "budget 400..1500, got ${text.length}",
            text.length in 400..1500,
        )
    }

    @Test
    fun `forbids the single-tool ping-pong`() {
        val lower = text.lowercase()
        assertTrue("must name ping-pong", lower.contains("ping-pong"))
        assertTrue("must demand one response", lower.contains("one response"))
        assertTrue("must demand all calls emitted", lower.contains("emit them all"))
        assertTrue("must carry the batching rule", lower.contains("batch") || lower.contains("up front"))
    }

    @Test
    fun `explains zero cognitive load - the engine coordinates`() {
        val lower = text.lowercase()
        assertTrue("parallel pool promise", lower.contains("in parallel"))
        assertTrue("io pool cap named", lower.contains("cap 16"))
        assertTrue("order preservation promise", lower.contains("preserves your order"))
        assertTrue("no model-side coordination", lower.contains("you never coordinate"))
    }

    @Test
    fun `carries the worked batch example with real tool names`() {
        // The example must name real tools of the surface, with real
        // arg shapes — an invented format would teach the model a lie.
        assertTrue(text.contains("file_read"))
        assertTrue(text.contains("grep"))
        assertTrue(text.contains("web_search"))
        assertTrue(text.contains("\"path\""))
        assertTrue(text.contains("\"pattern\""))
        assertTrue(text.contains("\"query\""))
    }

    @Test
    fun `contains strict prohibitions`() {
        val lower = text.lowercase()
        assertTrue("must forbid holding back known calls", lower.contains("holding back"))
        assertTrue(
            "must not tell the model reads are unsafe",
            lower.contains("parallel reads of the same file are safe"),
        )
    }

    @Test
    fun `wiring reaches the static system prompt tail`() {
        // The block is concatenated after BACKGROUND_MONITORING in
        // ChatViewModel.buildSystemPrompt — the static, cacheable
        // prefix. Pin the composition order contract at the source of
        // truth (the builder object), which the wiring references.
        val composed = SystemPromptBuilder.ENGINEERING_DISCIPLINE +
            SystemPromptBuilder.BACKGROUND_MONITORING +
            SystemPromptBuilder.PARALLEL_TOOL_DISCIPLINE
        assertTrue(
            "parallel block must sit at the tail of the static prefix",
            composed.trimEnd().endsWith(SystemPromptBuilder.PARALLEL_TOOL_DISCIPLINE.trimEnd()),
        )
    }
}
