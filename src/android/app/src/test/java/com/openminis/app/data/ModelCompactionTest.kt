package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCompactionTest {
    @Test
    fun `source chunks preserve every source character exactly once`() {
        val sources = listOf(
            ModelCompaction.Source("m1", "короткий пользовательский текст"),
            ModelCompaction.Source("m2", buildString {
                repeat(2_000) { append("строка-$it /var/minis/shared/p-$it\n") }
            }),
            ModelCompaction.Source("m3", "tail-\uD83E\uDDEA-end"),
        )
        val batches = ModelCompaction.planBatches(sources, maxBatchTokens = 700)

        assertTrue("large source must be split", batches.size > 2)
        assertTrue(batches.all { ModelCompaction.conservativeTokenUpperBound(it.render()) <= 700 })
        for (source in sources) {
            val reconstructed = batches
                .flatMap { it.segments }
                .filter { it.sourceId == source.id }
                .sortedBy { it.segmentIndex }
                .joinToString(separator = "") { it.text }
            assertEquals(source.text, reconstructed)
        }
    }

    @Test
    fun `transcript keeps full text tool arguments and tool results without duplicate text`() {
        val longOutput = "RESULT:" + "x".repeat(8_000) + ":END"
        val messages = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "build it",
                contentParts = listOf(AgentContentPart.Text("build it")),
                dbMessageId = "user-1",
            ),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "running",
                contentParts = listOf(
                    AgentContentPart.Text("running"),
                    AgentContentPart.ToolUse(
                        id = "tool-1",
                        name = "shell_execute",
                        input = JSONObject().put("command", "./gradlew assembleRelease"),
                    ),
                ),
                dbMessageId = "assistant-1",
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult(
                        id = "tool-1",
                        name = "shell_execute",
                        content = longOutput,
                    ),
                ),
                dbMessageId = "result-1",
            ),
        )

        val rendered = ModelCompaction.sourcesFrom(messages).joinToString("\n") { it.text }
        assertEquals(1, Regex("build it").findAll(rendered).count())
        assertEquals(1, Regex("running").findAll(rendered).count())
        assertTrue(rendered.contains("./gradlew assembleRelease"))
        assertTrue(rendered.contains(longOutput))
        assertTrue(rendered.contains("tool-1"))
    }

    @Test
    fun `budget reserves system output and safety space inside model window`() {
        val budget = ModelCompaction.budget(
            contextWindowTokens = 32_000,
            fixedPrompt = "system".repeat(400),
        )

        assertTrue(budget.maxBatchTokens >= 1_024)
        assertTrue(budget.maxOutputTokens >= 1_024)
        assertTrue(
            budget.fixedPromptTokens + budget.maxBatchTokens +
                budget.maxOutputTokens + budget.safetyTokens <= budget.contextWindowTokens,
        )
    }
}
