package com.openminis.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.DeletedMessageEntity
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [B30-stage2] The bounded-archive contract. `deleted_messages` had NO
 * delete anywhere in the codebase — every retry/edit/rerun row and every
 * deleted session's FULL transcript lived forever (the 408 MB minis.db).
 * These tests pin the sweeps that bound it, plus the session-delete
 * transaction that takes its archive rows along.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DeletedArchiveMaintenanceTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private val dao get() = db.chatDao()

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ChatRepository(db.chatDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertSession(id: String) {
        dao.insertSession(
            ChatSessionEntity(id = id, modelId = "m", createdAt = 1L, updatedAt = 1L),
        )
    }

    private fun archiveRow(sessionId: String, deletedAt: Long, tag: String) = DeletedMessageEntity(
        archiveId = "$sessionId-$deletedAt-$tag",
        messageId = "msg-$sessionId-$deletedAt-$tag",
        sessionId = sessionId,
        role = "user",
        partsJson = """{"type":"text","value":"body $tag"}""",
        createdAt = 1_000L,
        sortOrder = 0,
        deletedAt = deletedAt,
        archiveReason = "retry",
    )

    @Test
    fun `orphan purge drops rows whose session row is gone`() = runBlocking {
        insertSession("alive")
        dao.insertDeletedMessages(
            listOf(
                archiveRow("alive", 5_000L, "keep"),
                archiveRow("ghost", 6_000L, "orphan"),
            ),
        )
        val purged = dao.purgeOrphanDeletedMessages()
        assertEquals(1, purged)
        assertEquals(1, dao.deletedMessageCount("alive"))
        assertEquals(0, dao.deletedMessageCount("ghost"))
    }

    @Test
    fun `session delete takes its archive rows in the same transaction`() = runBlocking {
        insertSession("s1")
        insertSession("s2")
        dao.insertDeletedMessages(
            listOf(
                archiveRow("s1", 1_000L, "a"),
                archiveRow("s1", 2_000L, "b"),
                archiveRow("s2", 3_000L, "c"),
            ),
        )
        repo.deleteSession("s1")
        assertNull(dao.getSession("s1"))
        assertEquals(0, dao.deletedMessageCount("s1"))
        // An unrelated session's archive is untouched.
        assertEquals(1, dao.deletedMessageCount("s2"))
    }

    @Test
    fun `retention purge drops rows older than the cutoff`() = runBlocking {
        insertSession("s1")
        dao.insertDeletedMessages(
            listOf(
                archiveRow("s1", 999_999L, "old"),
                archiveRow("s1", 1_000_001L, "fresh"),
            ),
        )
        val purged = dao.purgeDeletedMessagesOlderThan(1_000_000L)
        assertEquals(1, purged)
        assertEquals(1, dao.deletedMessageCount("s1"))
    }

    @Test
    fun `per-session cap keeps the newest 100 and drops the oldest tail`() = runBlocking {
        insertSession("s1")
        val rows = (0 until 130).map { i -> archiveRow("s1", i * 1_000L, "r$i") }
        dao.insertDeletedMessages(rows)
        val purged = dao.capDeletedMessagesPerSession(100)
        assertEquals(30, purged)
        assertEquals(100, dao.deletedMessageCount("s1"))
        val survivors = dao.loadDeletedMessages("s1") // newest deletion first
        // The oldest survivor is the 31st row by deletedAt (0-based 30).
        assertEquals(30 * 1_000L, survivors.last().deletedAt)
        assertEquals(129 * 1_000L, survivors.first().deletedAt)
    }

    @Test
    fun `repository maintenance entry point runs the full sweep`() = runBlocking {
        insertSession("alive")
        insertSession("doomed")
        dao.insertDeletedMessages(
            listOf(
                archiveRow("alive", 5_000L, "ok"),
                archiveRow("ghost", 6_000L, "orphan"),
                archiveRow("alive", 500L, "ancient"),
            ),
        )
        repo.runDeletedArchiveMaintenance(nowMs = 30L * 24 * 60 * 60 * 1000 + 10_000L)
        // Orphan gone; the ancient row (30d+ before nowMs) gone; "ok" stays.
        assertEquals(0, dao.deletedMessageCount("ghost"))
        assertEquals(1, dao.deletedMessageCount("alive"))
        assertEquals("ok", dao.loadDeletedMessages("alive").single().archiveId.substringAfterLast('-'))
    }
}
