package com.openminis.app.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ptrace-routing] Guards the routing guidance in the shell_execute
 * description.
 *
 * Every syscall issued through shell_execute is intercepted by the PRoot
 * ptrace layer, so `find /` and `grep -r` walk the tree one syscall at a
 * time: slow, and they heat the phone up. The app already ships two native
 * tools for exactly this (native_search over the bundled ripgrep, and
 * native_glob), but nothing told the model they existed - so agents kept
 * shelling out to grep and find, which is what produced the hanging adb
 * calls and the heat.
 *
 * The guidance lives in a tool description, which is exactly the kind of
 * prose that gets trimmed during a refactor, so it is pinned here.
 */
class ShellExecuteRoutingHintTest {

    private val description: String =
        com.openminis.app.tools.AgentTools.shellExecuteDefinition().description

    @Test
    fun descriptionPointsAgentsAtTheNativeSearchTools() {
        assertTrue(
            "shell_execute description no longer mentions native_search",
            description.contains("native_search"),
        )
        assertTrue(
            "shell_execute description no longer mentions native_glob",
            description.contains("native_glob"),
        )
    }

    @Test
    fun descriptionExplainsWhyTheShellIsSlowForTreeWalks() {
        assertTrue(
            "the ptrace cost must be stated, otherwise the hint reads as a style rule",
            description.contains("ptrace"),
        )
    }

    @Test
    fun descriptionNamesTheShellCommandsItReplaces() {
        for (cmd in listOf("grep -r", "find", "ls -R")) {
            assertTrue(
                "shell_execute description no longer maps $cmd to a native tool",
                description.contains(cmd),
            )
        }
    }

    @Test
    fun descriptionStillStatesWhatTheShellIsFor() {
        // The hint must not read as "never use the shell".
        assertTrue(
            "shell_execute lost its purpose statement",
            description.contains("Alpine Linux via PRoot"),
        )
    }
}
