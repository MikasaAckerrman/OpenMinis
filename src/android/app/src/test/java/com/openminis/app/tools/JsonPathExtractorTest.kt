package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [P1-io-concurrency] The scheduler's safety rule lives in the extractor:
 * a declared key makes calls schedulable; a missing argument must stay
 * UNDECLARED (GLOBAL lock) — never an empty key that would silently ride
 * a parallel wave.
 */
class JsonPathExtractorTest {

    @Test
    fun `webfetch declares a net key for a real url`() {
        assertEquals(
            setOf("net:https://example.com/a"),
            jsonObjectResourceKeys("webfetch", """{"url":"https://example.com/a"}"""),
        )
    }

    @Test
    fun `webfetch without url stays undeclared`() {
        assertNull(jsonObjectResourceKeys("webfetch", "{}"))
        assertNull(jsonObjectResourceKeys("webfetch", "{not json"))
    }

    @Test
    fun `fs tools keep their existing key shape`() {
        assertEquals(
            setOf("fs:/a.kt"),
            jsonObjectResourceKeys("file_write", """{"path":"/a.kt"}"""),
        )
    }
}
