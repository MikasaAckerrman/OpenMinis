package com.openminis.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HookEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var rulesFile: File
    private var now = 0L

    @Before
    fun setUp() {
        rulesFile = tmp.newFile("hooks.json")
        now = 0L
    }

    private fun engine(): HookEngine = HookEngine(
        rulesetPath = rulesFile.absolutePath,
        clock = { now },
    )

    private fun writeRules(json: String) {
        rulesFile.writeText(json)
        // The stat gate keys on (mtime, length) — force a distinct mtime so a
        // same-length rewrite within one mtime tick still invalidates.
        rulesFile.setLastModified(rulesFile.lastModified() + 2000)
        now += 10_000 // past the reload interval
    }

    @Test
    fun `pre block rule blocks`() {
        writeRules("""{"rules":[{"tool":"shell_execute","match":"rm -rf","action":"block","message":"no"}]}""")
        val v = engine().evaluatePre("shell_execute", """{"command":"rm -rf /"}""")
        assertEquals(HookEngine.Action.BLOCK, v!!.action)
        assertEquals("no", v.message)
    }

    @Test
    fun `pre warn rule warns with message`() {
        writeRules("""{"rules":[{"tool":"bg_run","match":"curl","action":"warn","message":"net access"}]}""")
        val v = engine().evaluatePre("bg_run", """{"command":"curl x"}""")
        assertEquals(HookEngine.Action.WARN, v!!.action)
        assertEquals("net access", v.message)
    }

    @Test
    fun `no matching rule returns null`() {
        writeRules("""{"rules":[{"tool":"shell_execute","match":"rm -rf","action":"block","message":"no"}]}""")
        assertNull(engine().evaluatePre("shell_execute", """{"command":"ls -la"}"""))
        assertNull(engine().evaluatePre("file_read", """{"command":"rm -rf /"}"""))
    }

    @Test
    fun `matching is case-insensitive`() {
        writeRules("""{"rules":[{"match":"rm -rf","action":"block","message":"no"}]}""")
        val v = engine().evaluatePre("shell_execute", """{"command":"RM -RF /"}""")
        assertEquals(HookEngine.Action.BLOCK, v!!.action)
    }

    @Test
    fun `post rules fire only in post phase`() {
        writeRules("""{"rules":[{"tool":"file_write","match":"\\.kt","action":"warn","phase":"post","message":"format it"}]}""")
        val e = engine()
        assertNull(e.evaluatePre("file_write", """{"path":"/x.kt"}"""))
        assertEquals("format it", e.evaluatePost("file_write", """{"path":"/x.kt"}""")!!.message)
    }

    @Test
    fun `post block degrades to warn`() {
        writeRules("""{"rules":[{"match":"x","action":"block","phase":"post","message":"too late"}]}""")
        val v = engine().evaluatePost("file_write", """{"path":"x"}""")
        assertEquals(HookEngine.Action.WARN, v!!.action)
        assertEquals("too late", v.message)
    }

    @Test
    fun `malformed ruleset fails open`() {
        writeRules("this is not json")
        assertNull(engine().evaluatePre("shell_execute", """{"command":"rm -rf /"}"""))
    }

    @Test
    fun `missing ruleset fails open`() {
        rulesFile.delete()
        assertNull(engine().evaluatePre("shell_execute", """{"command":"rm -rf /"}"""))
    }

    @Test
    fun `blank message gets a default`() {
        writeRules("""{"rules":[{"match":"x","action":"warn"}]}""")
        assertEquals("hook engaged", engine().evaluatePre("t", "x")!!.message)
    }

    @Test
    fun `match input is capped - rule beyond the cap does not engage`() {
        writeRules("""{"rules":[{"match":"rm -rf","action":"block","message":"no"}]}""")
        val padded = """{"command":"${"a".repeat(2100)} rm -rf /"}"""
        assertNull(engine().evaluatePre("shell_execute", padded))
    }

    @Test
    fun `ruleset reloads on content change`() {
        writeRules("""{"rules":[{"match":"alpha","action":"block","message":"v1"}]}""")
        val e = engine()
        assertEquals(HookEngine.Action.BLOCK, e.evaluatePre("t", "alpha")!!.action)
        assertNull(e.evaluatePre("t", "beta"))
        writeRules("""{"rules":[{"match":"beta","action":"block","message":"v2"}]}""")
        assertNull(e.evaluatePre("t", "alpha"))
        assertEquals(HookEngine.Action.BLOCK, e.evaluatePre("t", "beta")!!.action)
    }
}
