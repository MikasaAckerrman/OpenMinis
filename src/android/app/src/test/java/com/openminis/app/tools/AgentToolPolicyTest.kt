package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The per-agent tool allowlist is the boundary that keeps a graph node
 * restricted to file_read from writing files. These tests pin the invariants
 * the two enforcement layers rely on: the schema filter in
 * [AgentTools.makeAgentTools] and the executor-level check in ChatViewModel.
 *
 * Both call [AgentTools.isToolPermitted] with the same alias expansion, so a
 * tool reachable under one name must be reachable under all of its aliases -
 * and a tool outside the list must be refused under every spelling.
 */
class AgentToolPolicyTest {

    @Before
    fun setUp() = AgentToolPolicyStore.clearAll()

    @After
    fun tearDown() = AgentToolPolicyStore.clearAll()

    @Test
    fun unknownSessionHasNoPolicyAndIsUnrestricted() {
        assertNull("a session with no entry must report no policy", AgentToolPolicyStore.policyFor("never-registered"))
        assertTrue(
            "no policy means unrestricted",
            AgentTools.isToolPermitted("file_write", AgentToolPolicyStore.policyFor("never-registered")),
        )
    }

    @Test
    fun policyIsScopedToItsOwnSession() {
        AgentToolPolicyStore.setPolicy("worker-a", listOf("file_read"))

        assertEquals(listOf("file_read"), AgentToolPolicyStore.policyFor("worker-a"))
        assertNull("another session must not inherit it", AgentToolPolicyStore.policyFor("worker-b"))

        assertTrue(AgentTools.isToolPermitted("file_read", AgentToolPolicyStore.policyFor("worker-a")))
        assertFalse(
            "a restricted session must not permit an outside tool",
            AgentTools.isToolPermitted("file_write", AgentToolPolicyStore.policyFor("worker-a")),
        )
        assertTrue(
            "an unrelated session stays unrestricted",
            AgentTools.isToolPermitted("file_write", AgentToolPolicyStore.policyFor("worker-b")),
        )
    }

    @Test
    fun aliasesResolveToTheSameTool() {
        val policy = listOf("file_edit")
        AgentTools.isToolPermitted("file_edit", policy).let { assertTrue(it) }
        AgentTools.isToolPermitted("file-edit", policy).let { assertTrue("underscore and dash must agree", it) }
        AgentTools.isToolPermitted("edit", policy).let { assertTrue("the short alias must agree", it) }
        AgentTools.isToolPermitted("file_write", policy).let { assertFalse(it) }
    }

    @Test
    fun memoryAliasExpandsToBothHalves() {
        val policy = listOf("memory")
        assertTrue(AgentTools.isToolPermitted("memory_write", policy))
        assertTrue(AgentTools.isToolPermitted("memory_get", policy))
        assertFalse(AgentTools.isToolPermitted("file_write", policy))
    }

    @Test
    fun emptyAllowlistMeansUnspecifiedNotMuted() {
        AgentToolPolicyStore.setPolicy("worker", listOf("file_read"))
        assertFalse(
            "before clearing, the restriction stands",
            AgentTools.isToolPermitted("file_write", AgentToolPolicyStore.policyFor("worker")),
        )

        AgentToolPolicyStore.setPolicy("worker", emptyList())

        assertNull("an empty allowlist clears the restriction", AgentToolPolicyStore.policyFor("worker"))
        assertTrue(
            "unspecified must not be read as no tools at all",
            AgentTools.isToolPermitted("file_write", AgentToolPolicyStore.policyFor("worker")),
        )
    }

    @Test
    fun clearingRemovesThePolicy() {
        AgentToolPolicyStore.setPolicy("worker", listOf("file_read"))
        AgentToolPolicyStore.clearPolicy("worker")
        assertNull(AgentToolPolicyStore.policyFor("worker"))

        AgentToolPolicyStore.setPolicy("worker", listOf("file_read"))
        AgentToolPolicyStore.clearAll()
        assertNull(AgentToolPolicyStore.policyFor("worker"))
    }
}
