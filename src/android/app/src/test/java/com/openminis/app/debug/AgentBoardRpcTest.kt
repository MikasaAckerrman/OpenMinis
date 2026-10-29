package com.openminis.app.debug

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-agent-board-rpc] The live board read must be advertised by rpc.discover
 * with the exact parameter names the handler reads - a misspelled param
 * (teamId vs team_id) would be silently ignored by optString and the filter
 * would never apply, which is exactly the silent-death failure mode this
 * RPC exists to fix.
 */
class AgentBoardRpcTest {

    private fun spec(): JSONObject {
        val arr = DebugMethodRegistry.discover().getJSONArray("methods")
        return (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .first { it.optString("name") == "debug.agentBoard" }
    }

    @Test
    fun discoverAdvertisesAgentBoard() {
        val s = spec()
        assertTrue("params list must not be empty", s.getJSONArray("params").length() > 0)
        assertTrue("returns shape must be declared", s.optString("returns").isNotBlank())
    }

    @Test
    fun paramsMatchTheHandlerContract() {
        val params = spec().getJSONArray("params")
        val names = (0 until params.length()).map { params.getJSONObject(it).optString("name") }
        assertEquals(listOf("teamId", "activeOnly", "limit"), names)
    }

    @Test
    fun limitDefaultsAreWithinTheHandlerClamp() {
        val params = spec().getJSONArray("params")
        val limit = (0 until params.length())
            .map { params.getJSONObject(it) }
            .first { it.optString("name") == "limit" }
        val def = limit.opt("default")
        assertNotNull("limit must declare a default", def)
        val asLong = (def as? Long) ?: (def as? Int)?.toLong()
        assertNotNull("limit default must be numeric, was $def", asLong)
        assertTrue("limit default must stay inside coerceIn(1, 100)", asLong!! in 1..100)
    }
}
