package com.openminis.app.tools

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [B16-stage2b] Unit tests for MCP server allowlist enforcement
 * in [AgentMcpPolicyStore] and [McpCallTool].
 */
@RunWith(RobolectricTestRunner::class)
class AgentMcpPolicyTest {

    private val sessionId = "test-session-mcp"
    private val context by lazy { RuntimeEnvironment.getApplication() }

    @Before
    @After
    fun cleanUp() {
        AgentMcpPolicyStore.clearAll()
    }

    @Test
    fun `store lifecycle sets, retrieves, and clears policy`() {
        assertNull(AgentMcpPolicyStore.policyFor(sessionId))

        AgentMcpPolicyStore.setPolicy(sessionId, listOf(" GitHub ", "Gmail"))
        assertEquals(listOf("github", "gmail"), AgentMcpPolicyStore.policyFor(sessionId))

        AgentMcpPolicyStore.clearPolicy(sessionId)
        assertNull(AgentMcpPolicyStore.policyFor(sessionId))

        // Empty list clears policy
        AgentMcpPolicyStore.setPolicy(sessionId, listOf("github"))
        assertNotNull(AgentMcpPolicyStore.policyFor(sessionId))
        AgentMcpPolicyStore.setPolicy(sessionId, emptyList())
        assertNull(AgentMcpPolicyStore.policyFor(sessionId))
    }

    @Test
    fun `filterListOutput filters JSON array correctly`() {
        val input = JSONArray().apply {
            put(JSONObject(mapOf("name" to "github", "enabled" to true)))
            put(JSONObject(mapOf("name" to "slack", "enabled" to true)))
            put(JSONObject(mapOf("name" to "gmail", "enabled" to false)))
        }.toString()

        val filteredStr = McpCallTool.filterListOutput(input, listOf("github", "gmail"))
        val filtered = JSONArray(filteredStr)
        assertEquals(2, filtered.length())
        assertEquals("github", filtered.getJSONObject(0).getString("name"))
        assertEquals("gmail", filtered.getJSONObject(1).getString("name"))
    }

    @Test
    fun `filterListOutput filters JSON object with mcpServers key`() {
        val input = JSONObject().apply {
            put("mcpServers", JSONArray().apply {
                put(JSONObject(mapOf("name" to "github", "status" to "ok")))
                put(JSONObject(mapOf("name" to "jira", "status" to "ok")))
            })
        }.toString()

        val filteredStr = McpCallTool.filterListOutput(input, listOf("jira"))
        val filteredObj = JSONObject(filteredStr)
        val arr = filteredObj.getJSONArray("mcpServers")
        assertEquals(1, arr.length())
        assertEquals("jira", arr.getJSONObject(0).getString("name"))
    }

    @Test
    fun `mcp execute rejects disallowed server on action tools`() = runBlocking {
        AgentMcpPolicyStore.setPolicy(sessionId, listOf("github"))

        val args = JSONObject().apply {
            put("action", "tools")
            put("server", "slack")
        }.toString()

        val result = McpCallTool.execute(args, sessionId, context)
        assertFalse(result.success)
        assertTrue(result.output.contains("not allowed for this agent"))
        assertTrue(result.output.contains("slack"))
        assertTrue(result.output.contains("github"))
    }

    @Test
    fun `mcp execute rejects disallowed server on action call`() = runBlocking {
        AgentMcpPolicyStore.setPolicy(sessionId, listOf("github"))

        val args = JSONObject().apply {
            put("action", "call")
            put("server", "telegram")
            put("tool", "send_msg")
        }.toString()

        val result = McpCallTool.execute(args, sessionId, context)
        assertFalse(result.success)
        assertTrue(result.output.contains("not allowed for this agent"))
        assertTrue(result.output.contains("telegram"))
    }
}
