package com.openminis.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-supermemory] The pure shaping half of the bridge. The HTTP half is
 * exercised on-device (server present) — here we pin the CONTRACT:
 * bounded injection, silent degradation, tolerant formatting.
 */
class SupermemoryBridgeTest {

    private fun hit(content: String, score: Double = 1.0) =
        SupermemoryBridge.Hit(id = "h", content = content, score = score)

    @Test
    fun `no hits - no injection (no noise in context)`() {
        assertNull(SupermemoryBridge.buildInjection(emptyList()))
    }

    @Test
    fun `hits are capped at three`() {
        val text = SupermemoryBridge.buildInjection((1..5).map { hit("memory $it") })!!
        assertEquals(3, Regex("•").findAll(text).count())
    }

    @Test
    fun `long snippets truncated to one line with ellipsis`() {
        val long = "x".repeat(500)
        val text = SupermemoryBridge.buildInjection(listOf(hit(long)))!!
        val line = text.lines().last()
        assertTrue(line.endsWith("…"))
        assertTrue(line.length <= 222)
    }

    @Test
    fun `newlines inside a hit are flattened - injection stays compact`() {
        val text = SupermemoryBridge.buildInjection(listOf(hit("one\ntwo\nthree")))!!
        assertTrue(text.contains("one two three"))
    }

    @Test
    fun `header names the source tier`() {
        val text = SupermemoryBridge.buildInjection(listOf(hit("fact")))!!
        assertTrue(text.startsWith("Релевантные долгосрочные воспоминания (supermemory):"))
    }

    @Test
    fun `caps are small and fixed`() {
        assertEquals(3, SupermemoryBridge.MAX_INJECTED)
    }
}
