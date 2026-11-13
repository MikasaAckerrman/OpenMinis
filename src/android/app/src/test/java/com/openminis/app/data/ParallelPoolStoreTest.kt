package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B53-parallel-pool] The pool's decisions: round-robin over ENABLED
 * entries only, master OFF / empty / all-disabled → null (the caller keeps
 * the standard resolve chain — byte-for-byte old behavior). The JSON is
 * tolerant: a corrupt file yields OFF/empty, never a crash — the spawn path
 * must never break because a settings file got mangled.
 */
class ParallelPoolStoreTest {

    private fun entry(id: String, on: Boolean = true) =
        ParallelPoolStore.PoolEntry(
            modelEntryId = id, displayName = "model-$id", host = "host-$id", enabled = on,
        )

    @Test
    fun `master off means null even with a full pool`() {
        assertNull(
            ParallelPoolStore.pickFor(0, masterEnabled = false, entries = listOf(entry("a"), entry("b"))),
        )
    }

    @Test
    fun `empty or all-disabled pool means null`() {
        assertNull(ParallelPoolStore.pickFor(0, masterEnabled = true, entries = emptyList()))
        assertNull(
            ParallelPoolStore.pickFor(0, masterEnabled = true, entries = listOf(entry("a", on = false))),
        )
    }

    @Test
    fun `round robin cycles the enabled entries in pool order`() {
        val pool = listOf(entry("a"), entry("b", on = false), entry("c"))
        val picks = (0 until 5).map {
            ParallelPoolStore.pickFor(it, masterEnabled = true, entries = pool)?.modelEntryId
        }
        // "b" is disabled and never picked; the cycle is a -> c -> a -> c -> a.
        assertEquals(listOf("a", "c", "a", "c", "a"), picks)
    }

    @Test
    fun `json round trip preserves the owner's curation`() {
        val entries = listOf(entry("e1"), entry("e2", on = false))
        val json = ParallelPoolStore.toJson(masterEnabled = true, entries = entries)
        val (enabled, parsed) = ParallelPoolStore.parsePool(json)
        assertTrue(enabled)
        assertEquals(entries, parsed)
    }

    @Test
    fun `a corrupt pool file degrades to off-empty, never a crash`() {
        val (enabled, entries) = ParallelPoolStore.parsePool("{not json at all")
        assertEquals(false, enabled)
        assertTrue(entries.isEmpty())
        assertEquals(false to emptyList<ParallelPoolStore.PoolEntry>(), ParallelPoolStore.parsePool(null))
    }

    @Test
    fun `entries without an id are dropped on parse`() {
        val json = """{"on":true,"entries":[{"id":"","name":"ghost"},{"id":"real","name":"real-model","host":"h","on":false}]}"""
        val (enabled, entries) = ParallelPoolStore.parsePool(json)
        assertTrue(enabled)
        assertEquals(1, entries.size)
        assertEquals("real", entries[0].modelEntryId)
        assertEquals(false, entries[0].enabled)
    }
}
