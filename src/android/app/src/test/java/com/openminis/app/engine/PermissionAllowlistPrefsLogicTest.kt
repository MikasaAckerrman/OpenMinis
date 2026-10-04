package com.openminis.app.engine

import com.openminis.app.ui.chat.PermissionAllowlistPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionAllowlistPrefsLogicTest {

    private class FakeStore : PermissionAllowlistPrefs.Store {
        val map = HashMap<String, Set<String>>()
        override fun getStringSet(key: String): Set<String> = map[key] ?: emptySet()
        override fun putStringSet(key: String, values: Set<String>) { map[key] = values }
    }

    private val store = FakeStore()
    private val argsA = """{"command":"pip install requests"}"""
    private val argsB = """{"command":"pip install numpy"}"""

    @Test
    fun `learned call is allowed for its session`() {
        assertFalse(PermissionAllowlistPrefs.Logic.isAllowed(store, "s1", "shell_execute", argsA))
        PermissionAllowlistPrefs.Logic.allow(store, "s1", "shell_execute", argsA)
        assertTrue(PermissionAllowlistPrefs.Logic.isAllowed(store, "s1", "shell_execute", argsA))
    }

    @Test
    fun `a different payload is NOT covered - hash-scoped by design`() {
        PermissionAllowlistPrefs.Logic.allow(store, "s1", "shell_execute", argsA)
        assertFalse(PermissionAllowlistPrefs.Logic.isAllowed(store, "s1", "shell_execute", argsB))
    }

    @Test
    fun `a different tool is NOT covered`() {
        PermissionAllowlistPrefs.Logic.allow(store, "s1", "shell_execute", argsA)
        assertFalse(PermissionAllowlistPrefs.Logic.isAllowed(store, "s1", "file_write", argsA))
    }

    @Test
    fun `learnings never leak across sessions`() {
        PermissionAllowlistPrefs.Logic.allow(store, "s1", "shell_execute", argsA)
        assertFalse(PermissionAllowlistPrefs.Logic.isAllowed(store, "s2", "shell_execute", argsA))
    }

    @Test
    fun `blank session id never engages`() {
        PermissionAllowlistPrefs.Logic.allow(store, "", "shell_execute", argsA)
        assertFalse(PermissionAllowlistPrefs.Logic.isAllowed(store, "", "shell_execute", argsA))
    }

    @Test
    fun `clear wipes the session's learnings`() {
        PermissionAllowlistPrefs.Logic.allow(store, "s1", "shell_execute", argsA)
        PermissionAllowlistPrefs.Logic.clear(store, "s1")
        assertFalse(PermissionAllowlistPrefs.Logic.isAllowed(store, "s1", "shell_execute", argsA))
    }

    @Test
    fun `migrate carries learnings to the real id and empties the draft`() {
        PermissionAllowlistPrefs.Logic.allow(store, "__new__1", "shell_execute", argsA)
        PermissionAllowlistPrefs.Logic.migrate(store, fromDraft = "__new__1", toReal = "real1")
        assertTrue(PermissionAllowlistPrefs.Logic.isAllowed(store, "real1", "shell_execute", argsA))
        assertFalse(PermissionAllowlistPrefs.Logic.isAllowed(store, "__new__1", "shell_execute", argsA))
    }

    @Test
    fun `entries are stable across instances`() {
        assertEquals(
            PermissionAllowlistPrefs.Logic.entry("shell_execute", argsA),
            PermissionAllowlistPrefs.Logic.entry("shell_execute", argsA),
        )
        assertFalse(
            PermissionAllowlistPrefs.Logic.entry("shell_execute", argsA) ==
                PermissionAllowlistPrefs.Logic.entry("shell_execute", argsB),
        )
    }
}
