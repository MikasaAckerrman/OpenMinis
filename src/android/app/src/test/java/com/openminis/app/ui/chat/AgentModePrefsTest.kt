package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests AgentModePrefs.Logic against an in-memory store.
 *
 * Not testing through a real Context on purpose: this module runs unit tests with
 * `unitTests.isReturnDefaultValues = true` and no Robolectric, so
 * `getSharedPreferences` returns null and any Context-touching test fails in CI
 * for reasons that have nothing to do with the rules being checked here.
 */
class AgentModePrefsTest {

    private class FakeStore : AgentModePrefs.Store {
        val map = HashMap<String, Boolean>()
        override fun get(key: String): Boolean = map[key] ?: false
        override fun put(key: String, value: Boolean) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun `default is off`() {
        assertFalse(AgentModePrefs.Logic.isForced(FakeStore(), "s1"))
    }

    @Test
    fun `flag is per session`() {
        val store = FakeStore()
        AgentModePrefs.Logic.setForced(store, "s1", true)
        assertTrue(AgentModePrefs.Logic.isForced(store, "s1"))
        assertFalse(AgentModePrefs.Logic.isForced(store, "s2"))
    }

    @Test
    fun `turning off removes the entry instead of storing false`() {
        val store = FakeStore()
        AgentModePrefs.Logic.setForced(store, "s1", true)
        AgentModePrefs.Logic.setForced(store, "s1", false)
        assertFalse(AgentModePrefs.Logic.isForced(store, "s1"))
        assertEquals(emptySet<String>(), store.map.keys)
    }

    @Test
    fun `blank session id is inert`() {
        val store = FakeStore()
        AgentModePrefs.Logic.setForced(store, "", true)
        assertFalse(AgentModePrefs.Logic.isForced(store, ""))
        assertEquals(emptySet<String>(), store.map.keys)
    }

    @Test
    fun `draft promotion carries the flag and leaves no orphan`() {
        val store = FakeStore()
        AgentModePrefs.Logic.setForced(store, "__new__abc", true)
        AgentModePrefs.Logic.migrate(store, fromDraft = "__new__abc", toReal = "real-1")
        assertTrue(AgentModePrefs.Logic.isForced(store, "real-1"))
        assertFalse(AgentModePrefs.Logic.isForced(store, "__new__abc"))
        assertEquals(setOf(AgentModePrefs.Logic.keyFor("real-1")), store.map.keys)
    }

    @Test
    fun `promotion of an off draft does not switch the real session on`() {
        val store = FakeStore()
        AgentModePrefs.Logic.migrate(store, fromDraft = "__new__abc", toReal = "real-1")
        assertFalse(AgentModePrefs.Logic.isForced(store, "real-1"))
        assertEquals(emptySet<String>(), store.map.keys)
    }

    @Test
    fun `migrate to the same id keeps the flag`() {
        val store = FakeStore()
        AgentModePrefs.Logic.setForced(store, "s1", true)
        AgentModePrefs.Logic.migrate(store, fromDraft = "s1", toReal = "s1")
        assertTrue(AgentModePrefs.Logic.isForced(store, "s1"))
    }
}
