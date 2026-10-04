package com.openminis.app.engine

import com.openminis.app.ui.chat.PermissionModePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionModePrefsLogicTest {

    private class FakeStore : PermissionModePrefs.Store {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private val store = FakeStore()

    @Test
    fun `unset session reads as null - caller applies the default`() {
        assertNull(PermissionModePrefs.Logic.get(store, "s1"))
    }

    @Test
    fun `set and get roundtrip for every mode`() {
        for (mode in PermissionMode.entries) {
            PermissionModePrefs.Logic.set(store, "s1", mode)
            assertEquals(mode, PermissionModePrefs.Logic.get(store, "s1"))
        }
    }

    @Test
    fun `sessions are isolated`() {
        PermissionModePrefs.Logic.set(store, "s1", PermissionMode.PLAN)
        assertNull(PermissionModePrefs.Logic.get(store, "s2"))
    }

    @Test
    fun `garbage in prefs reads as null, never crashes`() {
        store.map[PermissionModePrefs.Logic.keyFor("s1")] = "YOLO_MODE"
        assertNull(PermissionModePrefs.Logic.get(store, "s1"))
    }

    @Test
    fun `blank session id never engages`() {
        PermissionModePrefs.Logic.set(store, "", PermissionMode.EDIT)
        assertNull(PermissionModePrefs.Logic.get(store, ""))
        assertNull(PermissionModePrefs.Logic.get(store, "  "))
    }

    @Test
    fun `migrate carries the mode and clears the draft`() {
        PermissionModePrefs.Logic.set(store, "__new__1", PermissionMode.EDIT)
        PermissionModePrefs.Logic.migrate(store, fromDraft = "__new__1", toReal = "real1")
        assertEquals(PermissionMode.EDIT, PermissionModePrefs.Logic.get(store, "real1"))
        assertNull(PermissionModePrefs.Logic.get(store, "__new__1"))
    }

    @Test
    fun `migrate of an unset draft leaves nothing behind`() {
        PermissionModePrefs.Logic.migrate(store, fromDraft = "__new__2", toReal = "real2")
        assertNull(PermissionModePrefs.Logic.get(store, "real2"))
        assertNull(PermissionModePrefs.Logic.get(store, "__new__2"))
    }
}
