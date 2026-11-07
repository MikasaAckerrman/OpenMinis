package com.openminis.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [B19] The root cause of "edit does not survive": rewriteMessageText wrote
 * parts_json through the bare DAO, and because `updateMessagePartsPreserveStamp`
 * keeps the original created_at, the SessionHistoryCache fingerprint
 * (rowCount + MAX(created_at)) stayed IDENTICAL — the next loadSession served
 * the old parsed history from the Java heap while the DB on disk held the
 * edit. These tests pin the repository wrapper as the ONLY entry point for
 * the stamp-preserving rewrite: it must both land the write AND kill the
 * cache entry, so the subsequent loadSession re-reads the DB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RewriteCacheInvalidationTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private val dao get() = db.chatDao()

    private val oldParts = """[{"type":"text","value":"original text"}]"""
    private val newParts = """[{"type":"text","value":"edited text"}]"""

    @Before
    fun setUp() = runBlocking {
        SessionHistoryCache.clear()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ChatRepository(db.chatDao())
        dao.insertSession(
            ChatSessionEntity(id = "s1", modelId = "m", createdAt = 1L, updatedAt = 1L),
        )
        dao.insertMessages(
            listOf(
                MessageEntity(
                    id = "msg1",
                    sessionId = "s1",
                    role = "user",
                    partsJson = oldParts,
                    createdAt = 1_000L,
                    sortOrder = 0,
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        SessionHistoryCache.clear()
        db.close()
    }

    private fun cacheSessionWithCurrentFingerprint() {
        val rows = dao.loadMessages("s1")
        SessionHistoryCache.put(
            sessionId = "s1",
            rows = rows,
            llmHistory = rows.map { LLMMessage(role = LLMMessage.Role.USER, content = "") },
            rowCount = rows.size,
            maxCreatedAt = rows.maxOf { it.createdAt },
        )
    }

    @Test
    fun `preserved stamp leaves the fingerprint unchanged (why the bug was invisible)`() = runBlocking {
        cacheSessionWithCurrentFingerprint()
        repo.updateMessagePartsPreserveStamp("s1", "msg1", newParts)
        val row = dao.getMessageById("msg1")
        // The stamp is preserved — rowCount and MAX(created_at) did not move,
        // so NOTHING but the explicit invalidation could catch this write.
        assertEquals(1_000L, row!!.createdAt)
        assertEquals(1, dao.messageCountForSession("s1"))
    }

    @Test
    fun `wrapper invalidates the session cache entry and lands the write`() = runBlocking {
        cacheSessionWithCurrentFingerprint()
        // Warm cache serves the pre-edit history — the fingerprint matches.
        assertNotNull(SessionHistoryCache.get("s1", expectRowCount = 1, expectMaxCreatedAt = 1_000L))

        repo.updateMessagePartsPreserveStamp("s1", "msg1", newParts)

        // Cache entry is gone: the next loadSession MUST re-read the DB and
        // parse the edited parts_json — the "edit reverts on reload" path is
        // structurally closed.
        assertNull(SessionHistoryCache.get("s1", expectRowCount = 1, expectMaxCreatedAt = 1_000L))
        // The write itself landed and verifies by read-back (the same guard
        // rewriteMessageText applies at :read-back).
        assertEquals(newParts, dao.getMessageById("msg1")!!.partsJson)
    }

    @Test
    fun `message-id index is invalidated for other cached sessions too`() = runBlocking {
        cacheSessionWithCurrentFingerprint()
        // A second cached entry keyed by another session id that references
        // the same message id via the message→session index (merged/forked
        // views): invalidateMessage drops the OWNING entry.
        val rows2 = dao.loadMessages("s1")
        SessionHistoryCache.put(
            sessionId = "s2-view",
            rows = rows2,
            llmHistory = rows2.map { LLMMessage(role = LLMMessage.Role.USER, content = "") },
            rowCount = rows2.size,
            maxCreatedAt = rows2.maxOf { it.createdAt },
        )
        repo.updateMessagePartsPreserveStamp("s1", "msg1", newParts)
        assertNull(SessionHistoryCache.get("s1", 1, 1_000L))
        assertNull(SessionHistoryCache.get("s2-view", 1, 1_000L))
    }
}
