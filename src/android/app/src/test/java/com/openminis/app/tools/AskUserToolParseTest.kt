package com.openminis.app.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-m12-askuser] Option-format parsing — the surface the model writes
 * against on every ask_user call. Wrong parsing = broken question chips.
 */
class AskUserToolParseTest {

    @Test
    fun `json array of strings parses`() {
        val raw = JSONArray("""["Deploy now","After tests"]""")
        val opts = AskUserTool.parseOptions(raw)!!
        assertEquals(2, opts.size)
        assertEquals("Deploy now", opts[0].label)
    }

    @Test
    fun `json array of objects parses label and description`() {
        val raw = JSONArray(
            """[{"label":"A","description":"first"},{"label":"B"}]""",
        )
        val opts = AskUserTool.parseOptions(raw)!!
        assertEquals("A", opts[0].label)
        assertEquals("first", opts[0].description)
        assertEquals("", opts[1].description)
    }

    @Test
    fun `embedded json array string parses`() {
        val opts = AskUserTool.parseOptions("""["yes","no"]""")!!
        assertEquals(2, opts.size)
    }

    @Test
    fun `separator list parses`() {
        val opts = AskUserTool.parseOptions("a; b | c\nd")!!
        assertEquals(4, opts.size)
        assertEquals(listOf("a", "b", "c", "d"), opts.map { it.label })
    }

    @Test
    fun `blank input yields null - free text mode`() {
        assertNull(AskUserTool.parseOptions(""))
        assertNull(AskUserTool.parseOptions("   "))
        assertNull(AskUserTool.parseOptions(JSONObject()))
    }

    @Test
    fun `option cap at six`() {
        val raw = JSONArray("[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\"]")
        val opts = AskUserTool.parseOptions(raw)!!
        assertEquals(6, opts.size)
    }

    @Test
    fun `empty array yields null`() {
        assertNull(AskUserTool.parseOptions(JSONArray()))
    }
}
