package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [Э4-round-cost] The wire cache must serialize each distinct tool list
 * exactly once: identical rebuilds of the same list (the engine re-creates
 * it every round) HIT; any content change MISSES and rebuilds. The key is
 * List.equals over the data classes — exact, no hash collisions.
 */
class ToolWireCacheTest {

    private fun def(
        name: String,
        description: String = "desc for $name",
        params: Map<String, AgentToolParam> = mapOf("x" to AgentToolParam("string", "p")),
    ) = AgentToolDefinition(
        name = name,
        description = description,
        parameters = params,
    )

    private fun listA() = listOf(def("alpha"), def("beta"))
    private fun listA2() = listOf(def("alpha"), def("beta")) // equal content, new instances
    private fun listB() = listOf(def("alpha"), def("beta", description = "changed"))
    private fun listC() = listOf(def("alpha"))

    @Test
    fun `identical rebuilds of the same list hit the cache`() {
        ToolWireCache.wire(emptyList()) // reset to a known state
        val w1 = ToolWireCache.wire(listA())
        val w2 = ToolWireCache.wire(listA2())
        assertSame("same content must reuse the cached array", w1.schemas, w2.schemas)
        assertEquals(w1.totalBytes, w2.totalBytes)
    }

    @Test
    fun `a changed description misses and rebuilds`() {
        ToolWireCache.wire(emptyList())
        val w1 = ToolWireCache.wire(listA())
        val w2 = ToolWireCache.wire(listB())
        assertNotSame("changed content must not reuse the array", w1.schemas, w2.schemas)
    }

    @Test
    fun `single slot alternation rebuilds honestly`() {
        ToolWireCache.wire(emptyList())
        val a = ToolWireCache.wire(listA())
        val c = ToolWireCache.wire(listC())
        assertNotSame(a.schemas, c.schemas)
        val a2 = ToolWireCache.wire(listA())
        assertNotSame("single-slot cache evicted A on C - honest rebuild", a.schemas, a2.schemas)
    }

    @Test
    fun `totalBytes equals the sum of per-schema serialized bytes`() {
        val tools = listA()
        val expected = tools.sumOf { it.toOpenAIJson().toString().toByteArray().size }
        val w = ToolWireCache.wire(tools)
        assertEquals(expected, w.totalBytes)
        assertEquals(tools.size, w.schemas.length())
    }
}
