package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-m6-equivalence] M6 migration gate: the registry-driven
 * makeAgentTools must reproduce the legacy builder EXACTLY — same names,
 * same order — across the feature-gate matrix. If this holds, the engine
 * owns the schema surface with zero behavioral drift; deleted in M7
 * together with the legacy oracle.
 */
class ToolSurfaceAdapterTest {

    private fun names(tools: List<com.openminis.app.data.model.AgentToolDefinition>) =
        tools.joinToString(",") { it.name }

    private fun assertEquivalent(
        supportsImageInput: Boolean,
        memoryEnabled: Boolean,
        allowedTools: List<String>?,
        subagentsEnabled: Boolean,
        coreMemoryEnabled: Boolean,
        rootShellEnabled: Boolean,
        planMode: Boolean,
    ) {
        val legacy = AgentTools.legacyMakeAgentTools(
            supportsImageInput, memoryEnabled, allowedTools,
            subagentsEnabled, coreMemoryEnabled, rootShellEnabled, planMode,
        )
        val registry = AgentTools.makeAgentTools(
            supportsImageInput, memoryEnabled, allowedTools,
            subagentsEnabled, coreMemoryEnabled, rootShellEnabled, planMode,
        )
        assertEquals(
            "gate matrix: img=$supportsImageInput mem=$memoryEnabled " +
                "allow=$allowedTools sub=$subagentsEnabled core=$coreMemoryEnabled " +
                "root=$rootShellEnabled plan=$planMode",
            names(legacy), names(registry),
        )
    }

    @Test
    fun `registry equals legacy across the full gate matrix`() {
        for (planMode in listOf(false, true)) {
            for (memory in listOf(false, true)) {
                for (subagents in listOf(false, true)) {
                    assertEquivalent(
                        supportsImageInput = true,
                        memoryEnabled = memory,
                        allowedTools = null,
                        subagentsEnabled = subagents,
                        coreMemoryEnabled = false,
                        rootShellEnabled = false,
                        planMode = planMode,
                    )
                }
            }
        }
    }

    @Test
    fun `registry equals legacy with allowlists`() {
        assertEquivalent(
            true, true, listOf("file_read", "shell"), true, false, false, false,
        )
        assertEquivalent(
            true, true, listOf("memory"), false, false, false, true,
        )
    }

    @Test
    fun `plan mode drops write tools through the registry too`() {
        val schema = AgentTools.makeAgentTools(
            memoryEnabled = true, planMode = true,
        )
        val planTools = schema.map { it.name }
        org.junit.Assert.assertTrue(planTools.contains("plan_submit"))
        org.junit.Assert.assertFalse(planTools.contains("file_write"))
        org.junit.Assert.assertFalse(planTools.contains("file_edit"))
        org.junit.Assert.assertFalse(planTools.contains("memory_write"))
        // read/execute stay visible for exploration
        org.junit.Assert.assertTrue(planTools.contains("file_read"))
        org.junit.Assert.assertTrue(planTools.contains("shell_execute"))
    }

    @Test
    fun `adapter shells dispatch through the context seam`() {
        val shell = com.openminis.app.tools.EngineToolShell(
            "file_read",
            FileReadTool.definition(),
        )
        assertEquals("file_read", shell.name)
        assertEquals(
            com.openminis.app.engine.MutationKind.READ,
            shell.mutation,
        )
        // default context refuses execution — engine-only constructions
        // cannot run anything by accident.
        var thrown = false
        try {
            kotlinx.coroutines.runBlocking {
                shell.execute("{}", com.openminis.app.engine.ToolContext("s1"))
            }
        } catch (e: IllegalStateException) {
            thrown = true
        }
        org.junit.Assert.assertTrue(thrown)
        // wired context reaches the implementation through the seam.
        var seen = ""
        val wired = com.openminis.app.engine.ToolContext(
            "s1",
            dispatch = { name, _ ->
                seen = name
                ToolExecutionResult("ok", true, toolTitle = "t")
            },
        )
        kotlinx.coroutines.runBlocking { shell.execute("{}", wired) }
        assertEquals("file_read", seen)
    }
}
