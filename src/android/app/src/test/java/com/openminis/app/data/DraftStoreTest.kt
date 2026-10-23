package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        override fun keys(): Set<String> = map.keys.toSet()
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

    /**
     * [T-session-purge] Deleting a session must take everything the store
     * holds for it, not just its row in the database.
     */
    @Test
    fun `clearing a session removes every key the store holds for it`() {
        val store = FakeStore()
        DraftStore.Logic.saveDraft(store, "s1", "draft text")
        DraftStore.Logic.markSent(store, "s1", "sent text")
        store.put(DraftStore.Logic.queueKey("s1"), "queued")
        DraftStore.Logic.savePreEdit(store, "s1", "pre-edit text")

        DraftStore.Logic.clearSession(store, "s1")

        assertNull(store.get(DraftStore.Logic.draftKey("s1")))
        assertNull(store.get(DraftStore.Logic.sentKey("s1")))
        assertNull(store.get(DraftStore.Logic.queueKey("s1")))
        assertNull(store.get(DraftStore.Logic.preeditKey("s1")))
    }

    /**
     * [T-session-purge] The leftovers of sessions that are already gone are
     * reclaimed by the sweep.
     */
    @Test
    fun `the orphan sweep removes the keys of sessions that are gone`() {
        val store = FakeStore()
        DraftStore.Logic.saveDraft(store, "live", "keep")
        DraftStore.Logic.savePreEdit(store, "dead", "drop")
        store.put(DraftStore.Logic.queueKey("also-dead"), "drop")

        DraftStore.Logic.removeOrphans(store, setOf("live"))

        assertEquals("keep", store.get(DraftStore.Logic.draftKey("live")))
        assertNull(store.get(DraftStore.Logic.preeditKey("dead")))
        assertNull(store.get(DraftStore.Logic.queueKey("also-dead")))
    }

    /**
     * The sweep must not touch keys the store does not own.
     */
    @Test
    fun `the orphan sweep leaves keys the store does not own alone`() {
        val store = FakeStore()
        store.put("someone.else.key", "value")

        DraftStore.Logic.removeOrphans(store, setOf("live"))

        assertEquals("value", store.get("someone.else.key"))
    }

    /**
     * The pre-edit backup is part of the same store now, so it is swept
     * with everything else.
     */
    @Test
    fun `the pre-edit backup is saved, loaded and cleared`() {
        val store = FakeStore()
        DraftStore.Logic.savePreEdit(store, "s1", "typed but not sent")
        assertEquals("typed but not sent", DraftStore.Logic.loadPreEdit(store, "s1"))

        DraftStore.Logic.clearPreEdit(store, "s1")
        assertEquals("", DraftStore.Logic.loadPreEdit(store, "s1"))
    }
}
