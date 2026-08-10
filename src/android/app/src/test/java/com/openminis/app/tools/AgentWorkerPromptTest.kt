package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkerPromptTest {

    private val roleContract = """
        YOUR ROLE: SENIOR_IMPLEMENTER
        THE ONLY ARTIFACT I MAY PRODUCE: production code
        === HANDOFF FORMAT ===
        FROM: / TO: / STATUS:
    """.trimIndent()

    private fun build(tools: List<String> = listOf("file_read", "file_write", "shell_execute")) =
        AgentWorkerPrompt.build(
            assistantName = "Minis",
            roleContract = roleContract,
            allowedTools = tools,
            workspaceDir = "/var/minis/workspace/RUN-1",
        )

    @Test
    fun `prompt stays far below the general assistant prompt`() {
        // The general prompt measured 22 010 chars (~5 500 tokens) on PROBE-10.
        // The budget here is what makes adding a role affordable.
        val p = build()
        assertTrue(
            "worker prompt too large: ${p.length} chars",
            AgentWorkerPrompt.approximateTokens(p) < 1_500,
        )
    }

    @Test
    fun `no documentation for tools the worker cannot call`() {
        val p = build()
        for (absent in listOf(
            "android-alarm", "android-shizuku-cli", "minis-config", "minis-scheduled",
            "minis-model-use", "open_terminal", "Scheduled tasks", "GLOBAL.md",
        )) {
            assertFalse("leaked general-prompt section: $absent", p.contains(absent))
        }
    }

    @Test
    fun `only the allowed tools are described`() {
        val p = build(listOf("file_read", "shell_execute"))
        assertTrue(p.contains("file_read"))
        assertTrue(p.contains("shell_execute"))
        // A reviewer has no file_write; documenting it would invite the model to
        // try a call the schema will reject.
        assertFalse(p.contains("file_write:"))
        assertFalse(p.contains("browser_use"))
    }

    @Test
    fun `role contract is present and last`() {
        val p = build()
        assertTrue(p.contains("YOUR ROLE"))
        assertTrue(p.contains("HANDOFF"))
        assertTrue("role contract must end the prompt", p.trimEnd().endsWith("FROM: / TO: / STATUS:"))
    }

    @Test
    fun `worker is told it is not a general assistant`() {
        // The framing is the point: without it the model answers the user's
        // request in prose instead of emitting a handoff.
        assertTrue(build().contains("NOT a general assistant"))
    }

    @Test
    fun `shared workspace is stated so deliverables land where the reviewer looks`() {
        assertTrue(build().contains("/var/minis/workspace/RUN-1"))
        val noWs = AgentWorkerPrompt.build("Minis", roleContract, listOf("file_read"), null)
        assertFalse(noWs.contains("Shared workspace"))
    }

    @Test
    fun `unrestricted worker gets no tool dump`() {
        // Empty allowlist means "no restriction" elsewhere in the code; emitting
        // every tool's docs here would reintroduce the cost this class removes.
        val p = AgentWorkerPrompt.build("Minis", roleContract, emptyList(), null)
        assertFalse(p.contains("Your tools"))
        assertTrue(AgentWorkerPrompt.approximateTokens(p) < 400)
    }

    @Test
    fun `custom assistant name from SOUL is honoured`() {
        val p = AgentWorkerPrompt.build("Ada", roleContract, listOf("file_read"), null)
        assertTrue(p.startsWith("You are Ada,"))
    }

    @Test
    fun `unknown tool names are ignored rather than echoed`() {
        val p = AgentWorkerPrompt.build("Minis", roleContract, listOf("no_such_tool"), null)
        assertFalse(p.contains("no_such_tool"))
    }
}
