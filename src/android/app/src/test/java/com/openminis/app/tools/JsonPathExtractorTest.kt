package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [P1-io-concurrency] The scheduler's safety rule lives in the extractor:
 * a declared key makes calls schedulable; a missing argument must stay
 * UNDECLARED (GLOBAL lock) — never an empty key that would silently ride
 * a parallel wave.
 */
class JsonPathExtractorTest {

    @Test
    fun `webfetch declares a net key for a real url`() {
        assertEquals(
            setOf("net:https://example.com/a"),
            jsonObjectResourceKeys("webfetch", """{"url":"https://example.com/a"}"""),
        )
    }

    @Test
    fun `webfetch without url stays undeclared`() {
        assertNull(jsonObjectResourceKeys("webfetch", "{}"))
        assertNull(jsonObjectResourceKeys("webfetch", "{not json"))
    }

    @Test
    fun `fs tools keep their existing key shape`() {
        assertEquals(
            setOf("fs:/a.kt"),
            jsonObjectResourceKeys("file_write", """{"path":"/a.kt"}"""),
        )
    }
}

/**
 * [PARALLEL_TOOL_ENGINE_SPEC §3.1] The resource taxonomy that lifts the
 * false GLOBAL barriers: every key here is one that used to be `null`
 * (a whole-batch barrier) and now schedules precisely.
 */
class JsonPathExtractorTaxonomyTest {

    @Test
    fun `grep declares a tree read mirroring the executor default`() {
        assertEquals(
            setOf("fs_tree:/var/minis"),
            jsonObjectResourceKeys("grep", """{"pattern":"needle"}"""),
        )
        assertEquals(
            setOf("fs_tree:/var/minis/src"),
            jsonObjectResourceKeys("glob", """{"pattern":"*.rs","path":"/var/minis/src"}"""),
        )
    }

    @Test
    fun `web_search declares the shared search key`() {
        assertEquals(
            setOf("net:search"),
            jsonObjectResourceKeys("web_search", """{"query":"rust grammers"}"""),
        )
        // No args needed — the key is the search surface itself.
        assertEquals(setOf("net:search"), jsonObjectResourceKeys("web_search", "{}"))
    }

    @Test
    fun `the memory tier shares one store key for reads and writes`() {
        assertEquals(setOf("mem:store"), jsonObjectResourceKeys("memory_get", """{"key":"x"}"""))
        assertEquals(setOf("mem:store"), jsonObjectResourceKeys("todo_read", "{}"))
        assertEquals(setOf("mem:store"), jsonObjectResourceKeys("memory_write", """{"text":"x"}"""))
        assertEquals(setOf("mem:store"), jsonObjectResourceKeys("todo_write", """{"todos":[]}"""))
        assertEquals(setOf("mem:store"), jsonObjectResourceKeys("memory_blocks_edit", "{}"))
    }

    @Test
    fun `tg tools declare per-chat keys from chat_id and chat_ids`() {
        assertEquals(
            setOf("mcp:tg:chat_-100500"),
            jsonObjectResourceKeys("tg_read", """{"chat_id":-100500}"""),
        )
        assertEquals(
            setOf("mcp:tg:chat_1", "mcp:tg:chat_2", "mcp:tg:chat_3"),
            jsonObjectResourceKeys("tg_multi_search", """{"chat_ids":[1,2,3],"query":"x"}"""),
        )
    }

    @Test
    fun `chat-less tg tools collapse to the global read key`() {
        assertEquals(setOf("mcp:tg:global_read"), jsonObjectResourceKeys("tg_whoami", "{}"))
        assertEquals(setOf("mcp:tg:global_read"), jsonObjectResourceKeys("tg_list_chats", "{}"))
        assertEquals(setOf("mcp:tg:global_read"), jsonObjectResourceKeys("tg_scan_channels", "{}"))
        // A tg call with a chat_ids array that carries no valid ids.
        assertEquals(setOf("mcp:tg:global_read"), jsonObjectResourceKeys("tg_read_parallel", """{"chat_ids":[]}"""))
    }

    @Test
    fun `paths are textually canonicalized`() {
        assertEquals("src/main.rs", normalizeResourcePath("./src/main.rs"))
        assertEquals("src/main.rs", normalizeResourcePath("src/x/../main.rs"))
        assertEquals("/a/c", normalizeResourcePath("/a/b/../c"))
        assertEquals("/", normalizeResourcePath("/a/../.."))
        assertEquals("..", normalizeResourcePath(".."))
        assertEquals("a/c", normalizeResourcePath("a//c"))
    }

    @Test
    fun `normalized paths make textual variants one resource`() {
        val a = jsonObjectResourceKeys("file_read", """{"path":"./src/a.rs"}""")
        val b = jsonObjectResourceKeys("file_write", """{"path":"src/a.rs"}""")
        assertEquals(a, b)
    }

    @Test
    fun `a blank path still stays undeclared`() {
        assertNull(jsonObjectResourceKeys("file_read", """{"path":""}"""))
        assertNull(jsonObjectResourceKeys("file_read", "{not json"))
    }

    @Test
    fun `shell declares its file surface, browser and graph stay undeclared`() {
        // [B47-shell-parallel] A command with no visible file surface
        // declares an EMPTY key set (parallel-eligible under the CPU
        // gate), not the old undeclared GLOBAL lock that serialized every
        // shell against everything.
        assertEquals(
            emptySet<String>(),
            jsonObjectResourceKeys("shell_execute", """{"command":"ls"}"""),
        )
        assertNull(jsonObjectResourceKeys("browser_use", "{}"))
        assertNull(jsonObjectResourceKeys("run_graph", "{}"))
    }

    @Test
    fun `shell keys extract redirect targets and mentioned files`() {
        assertEquals(
            setOf("fs:notes.md", "fs:out.txt"),
            jsonObjectResourceKeys(
                "shell_execute",
                """{"command":"cat notes.md > out.txt"}""",
            ),
        )
        assertEquals(
            setOf("fs:/var/minis/workspace/a.txt"),
            jsonObjectResourceKeys(
                "shell_execute",
                """{"command":"echo hi >> /var/minis/workspace/a.txt"}""",
            ),
        )
    }

    @Test
    fun `dev-null and fd redirects are not file surfaces`() {
        assertEquals(
            emptySet<String>(),
            jsonObjectResourceKeys(
                "shell_execute",
                """{"command":"ls > /dev/null 2>&1"}""",
            ),
        )
    }
}
