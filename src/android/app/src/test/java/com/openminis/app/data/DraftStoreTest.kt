package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests DraftStore.Logic against an in-memory store — no Robolectric, mirroring
 * AgentModePrefsTest (this module's unit tests return default values and a real
 * Context's getSharedPreferences is null).
 */
class DraftStoreTest {

    private class FakeStore : DraftStore.Store {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun `draft saved and loaded`() {
        val s = FakeStore()
        DraftStore.Logic.saveDraft(s, "s1", "hello world")
        assertEquals("hello world", DraftStore.Logic.loadDraft(s, "s1"))
    }

    @Test
    fun `blank text clears the draft`() {
        val s = FakeStore()
        DraftStore.Logic.saveDraft(s, "s1", "hi")
        DraftStore.Logic.saveDraft(s, "s1", "")
        assertEquals("", DraftStore.Logic.loadDraft(s, "s1"))
        assertEquals(emptySet<String>(), s.map.keys)
    }

    @Test
    fun `markSent moves draft to backup and clears live draft`() {
        val s = FakeStore()
        DraftStore.Logic.saveDraft(s, "s1", "draft text")
        DraftStore.Logic.markSent(s, "s1", "sent text")
        assertEquals("", DraftStore.Logic.loadDraft(s, "s1"))
        assertEquals("sent text", DraftStore.Logic.peekLastSent(s, "s1"))
    }

    @Test
    fun `clearLastSent drops the backup`() {
        val s = FakeStore()
        DraftStore.Logic.markSent(s, "s1", "sent text")
        DraftStore.Logic.clearLastSent(s, "s1")
        assertEquals("", DraftStore.Logic.peekLastSent(s, "s1"))
        assertEquals(emptySet<String>(), s.map.keys)
    }

    @Test
    fun `blank send leaves no recoverable backup`() {
        val s = FakeStore()
        DraftStore.Logic.markSent(s, "s1", "")
        assertEquals("", DraftStore.Logic.peekLastSent(s, "s1"))
    }

    @Test
    fun `draft promotion carries slots and leaves no orphan`() {
        val s = FakeStore()
        // Separate sessions so the draft isn't consumed by markSent (which
        // moves the draft into the sent slot by design).
        DraftStore.Logic.saveDraft(s, "__new__abc", "typed on draft")
        DraftStore.Logic.migrate(s, fromSessionId = "__new__abc", toSessionId = "real-1")
        assertEquals("typed on draft", DraftStore.Logic.loadDraft(s, "real-1"))
        assertEquals("", DraftStore.Logic.loadDraft(s, "__new__abc"))

        val s2 = FakeStore()
        DraftStore.Logic.markSent(s2, "__new__def", "was sent")
        DraftStore.Logic.migrate(s2, fromSessionId = "__new__def", toSessionId = "real-2")
        assertEquals("was sent", DraftStore.Logic.peekLastSent(s2, "real-2"))
        assertEquals("", DraftStore.Logic.peekLastSent(s2, "__new__def"))
    }

    @Test
    fun `blank session id is inert`() {
        val s = FakeStore()
        DraftStore.Logic.saveDraft(s, "", "x")
        assertEquals("", DraftStore.Logic.loadDraft(s, ""))
        assertEquals(emptySet<String>(), s.map.keys)
    }

    @Test
    fun `migrate to same id is a no-op that preserves content`() {
        val s = FakeStore()
        DraftStore.Logic.saveDraft(s, "s1", "keep me")
        DraftStore.Logic.migrate(s, "s1", "s1")
        assertEquals("keep me", DraftStore.Logic.loadDraft(s, "s1"))
    }
}
