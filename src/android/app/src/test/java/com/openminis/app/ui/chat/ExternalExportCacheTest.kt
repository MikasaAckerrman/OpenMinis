package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report that started this: exporting chat B right after chat A was
 * refused with "свежий экспорт уже готов (N мин. назад)" about a file that
 * belonged to a different chat. The gate was one app-wide slot, so the only
 * thing that could have made it per-chat was a session comparison that the
 * export side did not perform.
 *
 * These pin the decisions, not the SharedPreferences plumbing - the pref
 * reads stay in the ViewModel so they can be exercised on a device.
 */
class ExternalExportCacheTest {

    private fun entry(
        atMs: Long,
        owner: String = "chat-A",
        uri: String = "content://export/1",
    ) = ExternalExportCache.Entry(
        sessionId = owner,
        uri = uri,
        atMs = atMs,
        where = "Download/Minis/ExportCompact/",
        firstDbId = "m1",
        lastDbId = "m9",
    )

    private val now = 1_700_000_000_000L

    /** The reported bug: chat B must not inherit chat A's fresh export. */
    @Test
    fun aFreshExportInOneChatDoesNotSatisfyAnother() {
        val rememberedForA = entry(now - 60_000) // exported a minute ago
        val pickedForB = ExternalExportCache.pickFresh(
            rememberedForA,
            sessionId = "chat-B",
            nowMs = now,
        )
        assertNull(
            "chat B must recompute; the entry belongs to chat A",
            pickedForB,
        )
    }

    @Test
    fun itsOwnFreshExportIsPicked() {
        val picked = ExternalExportCache.pickFresh(
            entry(now - 60_000),
            sessionId = "chat-A",
            nowMs = now,
        )
        assertNotNull("chat A's own export must be reused", picked)
        assertEquals("content://export/1", picked!!.uri)
    }

    @Test
    fun staleExportsAreRecomputed() {
        val stale = now - ExternalExportCache.FRESH_MS - 1
        assertNull(
            ExternalExportCache.pickFresh(entry(stale), "chat-A", now),
        )
    }

    /** A clock that moved backwards must not pin the user to a stale file. */
    @Test
    fun aTimestampFromTheFutureIsNotFresh() {
        assertNull(
            "future timestamps must recompute, not be trusted forever",
            ExternalExportCache.pickFresh(entry(now + 5_000), "chat-A", now),
        )
    }

    @Test
    fun anEntryWithoutARangeCannotBeImported() {
        val noRange = ExternalExportCache.Entry(
            sessionId = "chat-A",
            uri = "content://export/1",
            atMs = now - 1000,
            where = "x",
            firstDbId = null,
            lastDbId = null,
        )
        assertNull(ExternalExportCache.pickFresh(noRange, "chat-A", now))
    }

    /** Two chats exporting back to back must both stay remembered. */
    @Test
    fun rememberingASecondChatKeepsTheFirst() {
        val afterA = ExternalExportCache.remember(emptyList(), "chat-A")
        val afterB = ExternalExportCache.remember(afterA, "chat-B")
        assertEquals(listOf("chat-A", "chat-B"), afterB)
    }

    @Test
    fun reExportingMovesTheSessionToTheFrontWithoutDuplicating() {
        val after = listOf("chat-A", "chat-B", "chat-C")
            .let { ExternalExportCache.remember(it, "chat-A") }
        assertEquals(listOf("chat-B", "chat-C", "chat-A"), after)
        assertEquals(
            "no session may appear twice",
            after.size,
            after.distinct().size,
        )
    }

    /** The index must not grow without bound - that is the slow scan. */
    @Test
    fun theIndexIsBoundedAndEvictsTheOldest() {
        var index = emptyList<String>()
        repeat(ExternalExportCache.MAX_TRACKED + 5) { i ->
            index = ExternalExportCache.remember(index, "chat-$i")
        }
        assertEquals(ExternalExportCache.MAX_TRACKED, index.size)
        assertTrue(
            "the newest session must survive",
            index.contains("chat-${ExternalExportCache.MAX_TRACKED + 4}"),
        )
    }

    @Test
    fun evictedNamesExactlyTheDroppedSessions() {
        val previous = listOf("chat-0", "chat-1", "chat-2")
        val kept = listOf("chat-1", "chat-2")
        assertEquals(listOf("chat-0"), ExternalExportCache.evicted(previous, kept))
    }

    @Test
    fun theIndexSurvivesTheRoundTrip() {
        val ids = listOf("chat-A", "chat-B", "chat-C")
        assertEquals(
            ids,
            ExternalExportCache.parseIndex(ExternalExportCache.formatIndex(ids)),
        )
        assertEquals(emptyList<String>(), ExternalExportCache.parseIndex(null))
        assertEquals(emptyList<String>(), ExternalExportCache.parseIndex(""))
    }

    /** Every key of a session has to be removed together or the leak persists. */
    @Test
    fun eachSessionHasItsOwnDisjointKeys() {
        val a = ExternalExportCache.keysFor("chat-A").toSet()
        val b = ExternalExportCache.keysFor("chat-B").toSet()
        assertEquals(a.size, ExternalExportCache.keysFor("chat-A").size)
        assertTrue("keys must not overlap between sessions", a.intersect(b).isEmpty())
    }
}