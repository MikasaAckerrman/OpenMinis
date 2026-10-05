package com.openminis.app.tools

import com.openminis.app.engine.ToolContext
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-m11-seam] The execution leg of the M6 chain, end-to-end:
 * registry -> EngineToolShell -> ToolContext.dispatch -> the platform
 * executor. Schema equivalence is proven by ToolSurfaceAdapterTest; this
 * closes the execution seam — the same route the EngineAgentLoop swap
 * will drive in production.
 */
class ExecutionSeamTest {

    @Test
    fun `registry tools execute through the dispatch seam verbatim`() = runTest {
        val registry = ToolSurfaceAdapter.buildRegistry()
        val calls = mutableListOf<Pair<String, String>>()
        val ctx = ToolContext(
            sessionId = "s1",
            dispatch = { name, argsJson ->
                calls.add(name to argsJson)
                ToolExecutionResult("dispatched:$name", true)
            },
        )
        val tool = registry.find(FileReadTool.NAME)!!
        val result = tool.execute("{\"path\":\"/a.kt\"}", ctx)
        assertTrue(result.success)
        assertEquals("dispatched:file_read", result.output)
        // the platform executor received exactly the model's call
        assertEquals(listOf("file_read" to "{\"path\":\"/a.kt\"}"), calls)
    }

    @Test
    fun `raw args reach the executor byte-identical`() = runTest {
        val registry = ToolSurfaceAdapter.buildRegistry()
        val seen = mutableListOf<String>()
        val ctx = ToolContext(
            sessionId = "s1",
            dispatch = { _, argsJson ->
                seen.add(argsJson)
                ToolExecutionResult("ok", true)
            },
        )
        val raw = JSONObject(mapOf("path" to "/x y/файл.kt", "offset" to 3)).toString()
        registry.find(FileReadTool.NAME)!!.execute(raw, ctx)
        assertEquals(raw, seen.single())
    }
}
