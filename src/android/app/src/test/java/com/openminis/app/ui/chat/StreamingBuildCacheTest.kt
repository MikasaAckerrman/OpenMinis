package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingBuildCacheTest {

    @Test
    fun `offset split matches text split semantics`() {
        val content = "Para one\n\nPara two\nline continues\n\n```kotlin\nval x = 1\n```\n\nTail para"
        val frags = splitMarkdownIntoBlockFragments(content)
        val texts = splitMarkdownIntoBlockTexts(content)
        assertEquals(texts, frags.map { it.text })
        // Every reported start must reproduce the fragment text in content.
        for (f in frags) {
            val from = content.substring(f.start)
            assertTrue("fragment must be a prefix of content@start", from.startsWith(f.text.substringBefore('\n')))
        }
    }

    @Test
    fun `offset split handles unclosed fence while streaming`() {
        val content = "Intro\n\n```kotlin\nval x = 1"
        val frags = splitMarkdownIntoBlockFragments(content)
        assertEquals(2, frags.size)
        assertTrue(frags[1].text.contains("```kotlin"))
    }

    @Test
    fun `append growth reuses stable fragments and yields correct list`() {
        val base = "A\n\nB\n\n"
        val grown = "A\n\nB\n\nC is longer now\n\nD"
        val first = StreamingBuildCache.fragments("m1", "b1", base)
        val second = StreamingBuildCache.fragments("m1", "b1", grown)
        // Stable prefix strings must be the SAME instances (zero reallocation).
        assertTrue(second.size >= 2)
        assertTrue(second[0] === first[0])
        // Full rebuild of the grown content must match exactly.
        assertEquals(splitMarkdownIntoBlockTexts(grown), second)
    }

    @Test
    fun `append inside fence stays one fragment`() {
        val base = "```kotlin\nval a = 1"
        val grown = "```kotlin\nval a = 1\nval b = 2\nval c = 3"
        StreamingBuildCache.fragments("m2", "b1", base)
        val second = StreamingBuildCache.fragments("m2", "b1", grown)
        assertEquals(listOf(grown), second)
    }

    @Test
    fun `non-append change falls back to full split`() {
        StreamingBuildCache.fragments("m3", "b1", "first content\n\nmore")
        val rewritten = "REWRITTEN entirely different\n\nblocks"
        val result = StreamingBuildCache.fragments("m3", "b1", rewritten)
        assertEquals(splitMarkdownIntoBlockTexts(rewritten), result)
    }

    @Test
    fun `joined markdown grows incrementally and equals full join`() {
        val blocks = listOf("one", "two")
        val j1 = StreamingBuildCache.joinedMarkdown("m4", blocks) { blocks.joinToString("\n\n") }
        assertEquals("one\n\ntwo", j1)
        val grown = listOf("one", "two plus appended text")
        val j2 = StreamingBuildCache.joinedMarkdown("m4", grown) { grown.joinToString("\n\n") }
        assertEquals(grown.joinToString("\n\n"), j2)
        // Block-count change → full rebuild path.
        val three = listOf("one", "two plus appended text", "three")
        val j3 = StreamingBuildCache.joinedMarkdown("m4", three) { three.joinToString("\n\n") }
        assertEquals(three.joinToString("\n\n"), j3)
    }

    @Test
    fun `empty content`() {
        assertEquals(emptyList<String>(), StreamingBuildCache.fragments("m5", "b1", ""))
    }
}
