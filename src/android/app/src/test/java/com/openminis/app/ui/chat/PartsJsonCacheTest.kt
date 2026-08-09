package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PartsJsonCacheTest {

    private val validJson = """[{"type":"text","value":"hello"}]"""

    @Test
    fun `same row parsed once returns the identical document`() {
        val cache = PartsJsonCache()
        val a = cache.get("m1", validJson)
        val b = cache.get("m1", validJson)
        // Identity, not equality: a second JSONArray would mean we re-parsed.
        assertSame(a, b)
        assertEquals(1, cache.size)
    }

    @Test
    fun `different rows are cached separately`() {
        val cache = PartsJsonCache()
        cache.get("m1", validJson)
        cache.get("m2", """[{"type":"text","value":"world"}]""")
        assertEquals(2, cache.size)
    }

    @Test
    fun `parsed content is usable`() {
        val cache = PartsJsonCache()
        val arr = cache.get("m1", validJson)!!
        assertEquals(1, arr.length())
        assertEquals("text", arr.getJSONObject(0).optString("type"))
    }

    @Test
    fun `malformed json yields null and is not re-parsed`() {
        val cache = PartsJsonCache()
        assertNull(cache.get("bad", "{not json"))
        assertNull(cache.get("bad", "{not json"))
        // Cached as a negative result — one entry, no repeated throw cost.
        assertEquals(1, cache.size)
    }

    @Test
    fun `clear releases the parsed documents`() {
        val cache = PartsJsonCache()
        cache.get("m1", validJson)
        cache.clear()
        assertEquals(0, cache.size)
        // Still functional after clear (a fresh parse, new identity).
        assertTrue(cache.get("m1", validJson) != null)
        assertEquals(1, cache.size)
    }

    @Test
    fun `three passes over the same rows parse each row exactly once`() {
        // Mirrors the real open path: toolResult pass, UI pass, LLM pass.
        val cache = PartsJsonCache()
        val rows = (1..50).map { "m$it" to validJson }
        repeat(3) {
            for ((id, json) in rows) cache.get(id, json)
        }
        assertEquals(50, cache.size)
    }
}
