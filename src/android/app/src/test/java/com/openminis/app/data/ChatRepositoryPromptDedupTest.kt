package com.openminis.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [B-dedup-acceptor] Scenario B acceptance (store level): the acceptance
 * marker (source_prompt_ids) is atomic with the message row — the SAME
 * insert writes both, so acceptedPromptIds() observes exactly the ids of
 * the rows that exist. A process death between appendMessage and
 * persistPromptQueue therefore leaves a detectable trail: the restore
 * dedup drops the restored queue entry instead of re-delivering it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChatRepositoryPromptDedupTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private val sid = "dedup-session"

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ChatRepository(db.chatDao())
        // MessageEntity carries a FK to sessions — create the parent first.
        db.chatDao().insertSession(
            ChatSessionEntity(
                id = sid,
                modelId = "test-model",
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun partsJson(text: String) =
        """[{"type":"text","text":"$text"}]"""

    @Test
    fun `appendMessage with sourcePromptIds - marker durable with the row`() = runBlocking {
        repo.appendMessage(sid, "user", partsJson("hi"), sourcePromptIds = listOf("queued_1", "queued_2"))
        assertEquals(setOf("queued_1", "queued_2"), repo.acceptedPromptIds(sid))
    }

    @Test
    fun `appendMessage without sourcePromptIds - adds no marker`() = runBlocking {
        repo.appendMessage(sid, "user", partsJson("plain send"))
        assertTrue(repo.acceptedPromptIds(sid).isEmpty())
    }

    @Test
    fun `marker is per-session isolated`() = runBlocking {
        repo.appendMessage(sid, "user", partsJson("tagged"), sourcePromptIds = listOf("queued_x"))
        db.chatDao().insertSession(
            ChatSessionEntity(
                id = "other-session",
                modelId = "test-model",
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
        )
        repo.appendMessage("other-session", "user", partsJson("untagged"))
        assertEquals(setOf("queued_x"), repo.acceptedPromptIds(sid))
        assertTrue(repo.acceptedPromptIds("other-session").isEmpty())
    }

    @Test
    fun `malformed marker json - skipped, no throw, other rows still count`() = runBlocking {
        // A row written by some future/foreign binary with a corrupt column
        // must not break the dedup read (fail-open, never crash the restore).
        db.chatDao().insertMessage(
            MessageEntity(
                id = "corrupt-row",
                sessionId = sid,
                role = "user",
                partsJson = partsJson("corrupt marker"),
                createdAt = 2_000L,
                sortOrder = 0,
                sourcePromptIds = "not-json{",
            ),
        )
        repo.appendMessage(sid, "user", partsJson("good"), sourcePromptIds = listOf("queued_ok"))
        assertEquals(setOf("queued_ok"), repo.acceptedPromptIds(sid))
    }

    @Test
    fun `empty id list - no marker persisted (null, not empty json)`() = runBlocking {
        repo.appendMessage(sid, "user", partsJson("empty list"), sourcePromptIds = emptyList())
        assertTrue(repo.acceptedPromptIds(sid).isEmpty())
        // And the column stayed NULL, not "[]" — NULL is what non-queue rows
        // carry, so the DAO's IS NOT NULL filter keeps the result set small.
        val rows = db.chatDao().messageIdsForSession(sid)
        assertEquals(1, rows.size)
    }
}
