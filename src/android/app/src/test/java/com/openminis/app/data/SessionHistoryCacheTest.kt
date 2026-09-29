package com.openminis.app.data

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.LLMMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [T-session-history-cache] Unit contract for the cross-open parsed-history
 * cache: fingerprint hits/misses, LRU bound, and the two invalidation hooks
 * (session-level and message-id-level rewrite detection).
 */
class SessionHistoryCacheTest {

    private fun row(id: String, createdAt: Long) = MessageEntity(
        id = id,
        sessionId = "s1",
        role = "user",
        partsJson = "[]",
        createdAt = createdAt,
        sortOrder = 0,
    )

    private fun entry(rows: List<MessageEntity>) = rows to
        rows.map { LLMMessage(role = LLMMessage.Role.USER, content = "") }

    @Before
    fun reset() = SessionHistoryCache.clear()

    @After
    fun tearDown() = SessionHistoryCache.clear()

    @Test
    fun `fingerprint hit returns the parsed history`() {
        val (rows, llm) = entry(listOf(row("m1", 10), row("m2", 20)))
        SessionHistoryCache.put("s1", rows, llm, rowCount = 2, maxCreatedAt = 20)
        val hit = SessionHistoryCache.get("s1", expectRowCount = 2, expectMaxCreatedAt = 20)
        assertNotNull("identical fingerprint must hit", hit)
        assertEquals(2, hit!!.rows.size)
        assertEquals(llm, hit.llmHistory)
    }

    @Test
    fun `row count change invalidates`() {
        val (rows, llm) = entry(listOf(row("m1", 10)))
        SessionHistoryCache.put("s1", rows, llm, 1, 10)
        assertNull(
            "an appended row moves the count",
            SessionHistoryCache.get("s1", expectRowCount = 2, expectMaxCreatedAt = 10),
        )
    }

    @Test
    fun `maxCreatedAt change invalidates`() {
        val (rows, llm) = entry(listOf(row("m1", 10)))
        SessionHistoryCache.put("s1", rows, llm, 1, 10)
        assertNull(
            "a newer row moves MAX(created_at)",
            SessionHistoryCache.get("s1", expectRowCount = 1, expectMaxCreatedAt = 99),
        )
    }

    @Test
    fun `rewrite invalidation drops the owning session only`() {
        val (rowsA, llmA) = entry(listOf(row("a1", 10)))
        val (rowsB, llmB) = entry(listOf(row("b1", 10)))
        SessionHistoryCache.put("sA", rowsA, llmA, 1, 10)
        SessionHistoryCache.put("sB", rowsB, llmB, 1, 10)
        // rewrite b1 → only sB dies, sA survives
        SessionHistoryCache.invalidateMessage("b1")
        assertNull(SessionHistoryCache.get("sB", 1, 10))
        assertNotNull(SessionHistoryCache.get("sA", 1, 10))
    }

    @Test
    fun `lru caps at two sessions`() {
        for (i in 1..3) {
            val (rows, llm) = entry(listOf(row("m$i", i.toLong())))
            SessionHistoryCache.put("s$i", rows, llm, 1, i.toLong())
            SessionHistoryCache.get("s$i", 1, i.toLong()) // touch = MRU
        }
        // s2 was touched by its get AFTER s1's put; s1 is the eldest → evicted
        assertNull("eldest session evicted", SessionHistoryCache.get("s1", 1, 1))
        assertNotNull("2nd survives", SessionHistoryCache.get("s2", 1, 2))
        assertNotNull("3rd survives", SessionHistoryCache.get("s3", 1, 3))
    }
}
