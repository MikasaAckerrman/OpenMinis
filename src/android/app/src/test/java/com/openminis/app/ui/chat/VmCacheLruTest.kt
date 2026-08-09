package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VmCacheLruTest {
    @Test
    fun `oldest sessions are evicted once the cap is exceeded`() {
        val s = VmCacheLru.simulate(listOf("a", "b", "c", "d", "e"), maxResident = 3)
        assertEquals(listOf("c", "d", "e"), s.resident)
        assertEquals(listOf("a", "b"), s.evicted)
    }

    @Test
    fun `revisiting a session keeps it resident`() {
        // a is touched again before the cap is hit, so b must go instead.
        val s = VmCacheLru.simulate(listOf("a", "b", "c", "a", "d"), maxResident = 3)
        assertTrue("a" in s.resident)
        assertEquals(listOf("b"), s.evicted)
    }

    @Test
    fun `the active chat is never evicted even when it is the oldest`() {
        val s = VmCacheLru.simulate(
            listOf("active", "b", "c", "d", "e"),
            maxResident = 2,
            active = "active",
        )
        assertTrue("active" in s.resident)
        assertFalse("active" in s.evicted)
    }

    @Test
    fun `a streaming session is never evicted - its scope holds the live reply`() {
        val s = VmCacheLru.simulate(
            listOf("streaming", "b", "c", "d", "e", "f"),
            maxResident = 2,
            pinned = setOf("streaming"),
        )
        assertFalse("streaming" in s.evicted)
    }

    @Test
    fun `cache below the cap evicts nothing`() {
        val s = VmCacheLru.simulate(listOf("a", "b"), maxResident = 3)
        assertEquals(emptyList<String>(), s.evicted)
        assertEquals(listOf("a", "b"), s.resident)
    }

    @Test
    fun `every exempt session means the cache may exceed the cap rather than kill a stream`() {
        val s = VmCacheLru.simulate(
            listOf("a", "b", "c"),
            maxResident = 1,
            active = "c",
            pinned = setOf("a", "b"),
        )
        assertEquals(emptyList<String>(), s.evicted)
        assertEquals(3, s.resident.size)
    }
}
