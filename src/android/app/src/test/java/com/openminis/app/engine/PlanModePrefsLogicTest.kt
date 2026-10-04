package com.openminis.app.engine

import com.openminis.app.ui.chat.PlanModePrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanModePrefsLogicTest {

    private class FakeStore : PlanModePrefs.Store {
        val map = HashMap<String, Boolean>()
        override fun get(key: String): Boolean = map[key] ?: false
        override fun put(key: String, value: Boolean) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private val store = FakeStore()

    @Test
    fun `default is off`() {
        assertFalse(PlanModePrefs.Logic.isEnabled(store, "s1"))
    }

    @Test
    fun `enable then disable`() {
        PlanModePrefs.Logic.setEnabled(store, "s1", true)
        assertTrue(PlanModePrefs.Logic.isEnabled(store, "s1"))
        PlanModePrefs.Logic.setEnabled(store, "s1", false)
        assertFalse(PlanModePrefs.Logic.isEnabled(store, "s1"))
    }

    @Test
    fun `sessions are isolated`() {
        PlanModePrefs.Logic.setEnabled(store, "s1", true)
        assertFalse(PlanModePrefs.Logic.isEnabled(store, "s2"))
    }

    @Test
    fun `disable removes the entry instead of storing false`() {
        PlanModePrefs.Logic.setEnabled(store, "s1", true)
        PlanModePrefs.Logic.setEnabled(store, "s1", false)
        // Same discipline as AgentModePrefs: false is the default — a stored
        // false carries no information and must not accumulate as dead rows.
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `blank session id never engages`() {
        PlanModePrefs.Logic.setEnabled(store, "", true)
        assertFalse(PlanModePrefs.Logic.isEnabled(store, ""))
        assertFalse(PlanModePrefs.Logic.isEnabled(store, "  "))
    }

    @Test
    fun `migrate carries an armed draft to the real id and clears the draft`() {
        PlanModePrefs.Logic.setEnabled(store, "__new__1", true)
        PlanModePrefs.Logic.migrate(store, fromDraft = "__new__1", toReal = "real1")
        assertTrue(PlanModePrefs.Logic.isEnabled(store, "real1"))
        assertFalse(PlanModePrefs.Logic.isEnabled(store, "__new__1"))
    }

    @Test
    fun `migrate of a disarmed draft leaves nothing behind`() {
        PlanModePrefs.Logic.migrate(store, fromDraft = "__new__2", toReal = "real2")
        assertFalse(PlanModePrefs.Logic.isEnabled(store, "real2"))
        assertFalse(PlanModePrefs.Logic.isEnabled(store, "__new__2"))
    }
}
