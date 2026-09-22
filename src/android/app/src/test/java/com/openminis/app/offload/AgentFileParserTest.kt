package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-agent-file] The user-defined agent format contract — the file the user
 * (or the agent itself) writes must parse into exactly the config the
 * spawner will use. Format drift here breaks every custom agent at once.
 */
class AgentFileParserTest {

    private val full = """
        ---
        name: api-auditor
        description: "Audits REST APIs for auth, pagination and error handling"
        modelRole: reviewer
        tools: shell, file_read, write
        maxTurns: 12
        ---
        You are an API auditor. Your ONE job: review the API surface and report findings.
    """.trimIndent()

    @Test
    fun `full frontmatter parses with every field`() {
        val a = AgentFileParser.parse("api-auditor.md", full)
        assertNotNull(a)
        a!!
        assertEquals("api-auditor", a.name)
        assertEquals("Audits REST APIs for auth, pagination and error handling", a.description)
        assertEquals("reviewer", a.modelRole)
        assertEquals(listOf("shell_execute", "file_read", "file_write"), a.tools)
        assertEquals(12, a.maxTurns)
        assertTrue(a.instructions.startsWith("You are an API auditor"))
    }

    @Test
    fun `name falls back to the file name`() {
        val a = AgentFileParser.parse(
            "greeter.md",
            "---\ndescription: says hi\n---\nJust say hi.",
        )
        assertNotNull(a)
        assertEquals("greeter", a!!.name)
    }

    @Test
    fun `defaults apply when fields are omitted`() {
        val a = AgentFileParser.parse("x.md", "Do the thing, report it.")
        assertNotNull(a)
        assertNull(a!!.modelRole)
        assertNull(a.modelEntryId)
        assertNull(a.tools)
        assertEquals(AgentFileParser.AgentFile.DEFAULT_MAX_TURNS, a.maxTurns)
        assertEquals("Do the thing, report it.", a.instructions)
    }

    @Test
    fun `empty body is rejected`() {
        assertNull(AgentFileParser.parse("x.md", "---\nname: x\n---\n"))
        assertNull(AgentFileParser.parse("x.md", "   "))
    }

    @Test
    fun `a name containing a colon is rejected (would break custom-name routing)`() {
        assertNull(AgentFileParser.parse("x.md", "---\nname: a:b\n---\nbody"))
    }

    @Test
    fun `unknown modelRole and tools are dropped, not fatal`() {
        val a = AgentFileParser.parse(
            "x.md",
            "---\nmodelRole: wizard\ntools: shell, phaser, file_read\n---\nBody.",
        )
        assertNotNull(a)
        assertNull(a!!.modelRole)
        assertEquals(listOf("shell_execute", "file_read"), a.tools)
    }

    @Test
    fun `out-of-range maxTurns falls back to the default`() {
        val a = AgentFileParser.parse("x.md", "---\nmaxTurns: 999\n---\nBody.")
        assertEquals(AgentFileParser.AgentFile.DEFAULT_MAX_TURNS, a!!.maxTurns)
        val b = AgentFileParser.parse("x.md", "---\nmaxTurns: 0\n---\nBody.")
        assertEquals(AgentFileParser.AgentFile.DEFAULT_MAX_TURNS, b!!.maxTurns)
    }

    @Test
    fun `text without frontmatter is all body`() {
        val a = AgentFileParser.parse("x.md", "Plain instructions only.")
        assertEquals("Plain instructions only.", a!!.instructions)
    }

    @Test
    fun `comments and blank lines in frontmatter are ignored`() {
        val a = AgentFileParser.parse(
            "x.md",
            "---\n# a comment\n\nname: y\n\n# another\ndescription: d\n---\nBody.",
        )
        assertEquals("y", a!!.name)
        assertEquals("d", a.description)
    }
}
