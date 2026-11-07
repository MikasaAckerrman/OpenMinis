package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.offload.AgentFileParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B16-stage2] Unit tests for [AgentDraft] validation, markdown generation,
 * and roundtrip with [AgentFileParser].
 */
class AgentDraftTest {

    @Test
    fun `validate catches bad names`() {
        val base = AgentDraft.empty().copy(instructions = "Do something")

        // Invalid names
        assertEquals(R.string.agents_bad_name, base.copy(name = "").validate())
        assertEquals(R.string.agents_bad_name, base.copy(name = "   ").validate())
        assertEquals(R.string.agents_bad_name, base.copy(name = "name with spaces").validate())
        assertEquals(R.string.agents_bad_name, base.copy(name = "name/slash").validate())
        assertEquals(R.string.agents_bad_name, base.copy(name = "custom:agent").validate())

        // Valid names
        assertNull(base.copy(name = "api-auditor").validate())
        assertNull(base.copy(name = "Code_Reviewer_2").validate())
        assertNull(base.copy(name = "simple").validate())
    }

    @Test
    fun `validate catches empty instructions`() {
        val base = AgentDraft.empty().copy(name = "auditor")

        assertEquals(R.string.agents_empty_instructions, base.copy(instructions = "").validate())
        assertEquals(R.string.agents_empty_instructions, base.copy(instructions = "   \n\t").validate())
        assertNull(base.copy(instructions = "Valid instruction body").validate())
    }

    @Test
    fun `validate catches invalid maxTurns`() {
        val base = AgentDraft.empty().copy(name = "auditor", instructions = "Help me")

        assertEquals(R.string.agents_bad_max_turns, base.copy(maxTurnsText = "0").validate())
        assertEquals(R.string.agents_bad_max_turns, base.copy(maxTurnsText = "65").validate())
        assertEquals(R.string.agents_bad_max_turns, base.copy(maxTurnsText = "-5").validate())

        // Valid maxTurns (1..64 or non-integer defaults)
        assertNull(base.copy(maxTurnsText = "1").validate())
        assertNull(base.copy(maxTurnsText = "8").validate())
        assertNull(base.copy(maxTurnsText = "64").validate())
    }

    @Test
    fun `roundtrip markdown with AgentFileParser`() {
        val draft = AgentDraft(
            name = "db-optimizer",
            description = "Optimizes SQL queries and indexes",
            modelRole = "reviewer",
            modelEntryId = "claude-3-7-sonnet",
            maxTurnsText = "16",
            restrictTools = true,
            tools = setOf("file_read", "shell_execute"),
            instructions = "You are an expert DB engineer. Analyze slow queries and propose indexes.",
        )

        assertNull(draft.validate())
        val md = draft.toMarkdown()

        val parsed = AgentFileParser.parse("db-optimizer.md", md)
        assertNotNull(parsed)
        parsed!!

        assertEquals("db-optimizer", parsed.name)
        assertEquals("Optimizes SQL queries and indexes", parsed.description)
        assertEquals("reviewer", parsed.modelRole)
        assertEquals("claude-3-7-sonnet", parsed.modelEntryId)
        assertEquals(16, parsed.maxTurns)
        assertEquals(listOf("file_read", "shell_execute"), parsed.tools?.sorted())
        assertTrue(parsed.instructions.startsWith("You are an expert DB engineer"))

        // fromFile rebuilds equivalent draft
        val restored = AgentDraft.fromFile(parsed)
        assertEquals(draft.name, restored.name)
        assertEquals(draft.description, restored.description)
        assertEquals(draft.modelRole, restored.modelRole)
        assertEquals(draft.modelEntryId, restored.modelEntryId)
        assertEquals(draft.maxTurnsText, restored.maxTurnsText)
        assertTrue(restored.restrictTools)
        assertEquals(draft.tools, restored.tools)
        assertEquals(draft.instructions, restored.instructions)
    }

    @Test
    fun `unrestricted tools omits tools line in markdown`() {
        val draft = AgentDraft(
            name = "minimal-agent",
            description = "A minimal agent",
            modelRole = null,
            modelEntryId = null,
            maxTurnsText = "8",
            restrictTools = false,
            tools = setOf("file_read"),
            instructions = "Simple instruction.",
        )

        val md = draft.toMarkdown()
        assertFalse(md.contains("tools:"))

        val parsed = AgentFileParser.parse("minimal-agent.md", md)
        assertNotNull(parsed)
        parsed!!
        assertNull(parsed.tools)

        val restored = AgentDraft.fromFile(parsed)
        assertFalse(restored.restrictTools)
        assertTrue(restored.tools.isEmpty())
    }

    @Test
    fun `roundtrip mcpServers with AgentFileParser`() {
        val draft = AgentDraft(
            name = "github-bot",
            description = "Interacts with GitHub via MCP",
            modelRole = "coder",
            modelEntryId = null,
            maxTurnsText = "8",
            restrictTools = true,
            tools = setOf("mcp", "file_read"),
            restrictMcpServers = true,
            mcpServers = setOf("github", "gmail"),
            instructions = "Review PRs and send email summary.",
        )

        val md = draft.toMarkdown()
        assertTrue(md.contains("mcpServers: github, gmail"))

        val parsed = AgentFileParser.parse("github-bot.md", md)
        assertNotNull(parsed)
        parsed!!
        assertEquals(listOf("github", "gmail"), parsed.mcpServers?.sorted())

        val restored = AgentDraft.fromFile(parsed)
        assertTrue(restored.restrictMcpServers)
        assertEquals(setOf("github", "gmail"), restored.mcpServers)
    }

    @Test
    fun `unrestricted mcpServers omits mcpServers line in markdown`() {
        val draft = AgentDraft(
            name = "open-bot",
            description = "Accesses any MCP server",
            modelRole = null,
            modelEntryId = null,
            maxTurnsText = "8",
            restrictTools = true,
            tools = setOf("mcp"),
            restrictMcpServers = false,
            mcpServers = setOf("github"),
            instructions = "Instructions.",
        )

        val md = draft.toMarkdown()
        assertFalse(md.contains("mcpServers:"))

        val parsed = AgentFileParser.parse("open-bot.md", md)
        assertNotNull(parsed)
        assertNull(parsed!!.mcpServers)

        val restored = AgentDraft.fromFile(parsed)
        assertFalse(restored.restrictMcpServers)
        assertTrue(restored.mcpServers.isEmpty())
    }
}
