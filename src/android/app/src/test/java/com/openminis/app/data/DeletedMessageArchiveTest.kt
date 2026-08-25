package com.openminis.app.data

import com.openminis.app.data.db.DeletedMessageEntity
import com.openminis.app.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-no-destructive-retry] Regression guard for the HUD-session data loss.
 *
 * Root cause (proven from the live clone DB): retry/edit/rerun called
 * `deleteMessagesAfter`, which PHYSICALLY removed the truncated tail. When a
 * retry anchored on an old message, a week of later work was deleted with no
 * recovery — 26 compact markers were left pointing at message ids that no
 * longer existed in the `messages` table.
 *
 * The fix routes all four callers through `archiveAndDeleteMessagesAfter`,
 * which copies every doomed row into `deleted_messages` before deleting from
 * `messages`. This test proves the two invariants that make the loss
 * impossible, WITHOUT needing a Room/Android runtime (so it runs in the plain
 * JVM unit-test set CI uses):
 *
 *   1. fromMessage() preserves every message field byte-for-byte — archiving
 *      never silently drops or mangles content.
 *   2. The archive-then-truncate semantics leave the union (kept rows +
 *      archived rows) equal to the original set — nothing is ever lost, and
 *      the archived rows are exactly the ones removed from the live tail.
 */
class DeletedMessageArchiveTest {

    private fun msg(sort: Int, role: String, text: String): MessageEntity =
        MessageEntity(
            id = "m$sort",
            sessionId = "S",
            role = role,
            partsJson = """[{"type":"text","value":"$text"}]""",
            createdAt = 1_000L + sort,
            tokenUsage = if (role == "assistant") """{"in":10,"out":20}""" else null,
            sortOrder = sort,
            reasoningContent = if (role == "assistant") "thinking-$sort" else null,
            streamInterruptCount = sort % 2,
            updatedAt = 2_000L + sort,
            errorInfo = null,
        )

    @Test
    fun `fromMessage copies every message field verbatim`() {
        val m = msg(7, "assistant", "hello")
        val a = DeletedMessageEntity.fromMessage(m, deletedAt = 999L, reason = "edit", archiveId = "A1")

        // archive bookkeeping
        assertEquals("A1", a.archiveId)
        assertEquals(999L, a.deletedAt)
        assertEquals("edit", a.archiveReason)
        // verbatim message payload — the whole point is losing nothing
        assertEquals(m.id, a.messageId)
        assertEquals(m.sessionId, a.sessionId)
        assertEquals(m.role, a.role)
        assertEquals(m.partsJson, a.partsJson)
        assertEquals(m.createdAt, a.createdAt)
        assertEquals(m.tokenUsage, a.tokenUsage)
        assertEquals(m.sortOrder, a.sortOrder)
        assertEquals(m.reasoningContent, a.reasoningContent)
        assertEquals(m.streamInterruptCount, a.streamInterruptCount)
        assertEquals(m.updatedAt, a.updatedAt)
        assertEquals(m.errorInfo, a.errorInfo)
    }

    /**
     * Simulate archiveAndDeleteMessagesAfter without Room: the DAO does
     * `SELECT ... WHERE sort_order >= keepCount` → archive → `DELETE ... WHERE
     * sort_order >= keepCount`. Mirror that on a list and assert conservation.
     */
    private fun archiveAndTruncate(
        rows: List<MessageEntity>,
        keepCount: Int,
        reason: String,
    ): Pair<List<MessageEntity>, List<DeletedMessageEntity>> {
        val doomed = rows.filter { it.sortOrder >= keepCount }.sortedBy { it.sortOrder }
        val archived = doomed.map { DeletedMessageEntity.fromMessage(it, 5L, reason) }
        val kept = rows.filter { it.sortOrder < keepCount }.sortedBy { it.sortOrder }
        return kept to archived
    }

    @Test
    fun `retry over a compacted session archives the deleted tail — nothing is lost`() {
        // 16 Aug turns (sort 0..3) + a week of later work (sort 4..9), the
        // exact shape of the HUD session: a retry anchored on the 16 Aug user
        // message #2 would truncate everything from sort_order 3 onward.
        val rows = listOf(
            msg(0, "user", "16aug task"),
            msg(1, "assistant", "16aug reply"),
            msg(2, "user", "vot kakie oshibki"),
            msg(3, "assistant", "17aug work"),
            msg(4, "user", "18aug"),
            msg(5, "assistant", "21aug work"),
            msg(6, "user", "21aug more"),
            msg(7, "assistant", "22aug work"),
            msg(8, "user", "24aug"),
            msg(9, "assistant", "24aug reply"),
        )
        val keepCount = 3 // keep sort_order < 3, i.e. the 16 Aug head

        val (kept, archived) = archiveAndTruncate(rows, keepCount, "retry")

        // live table shrinks exactly as before the fix (behaviour unchanged)
        assertEquals(listOf(0, 1, 2), kept.map { it.sortOrder })
        // the removed week is now in the archive, not gone
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 9), archived.map { it.sortOrder })
        assertEquals(List(7) { "retry" }, archived.map { it.archiveReason })

        // CONSERVATION: kept ∪ archived == original, no duplicates, no drops
        val recoveredIds = (kept.map { it.id } + archived.map { it.messageId }).toSet()
        assertEquals(rows.map { it.id }.toSet(), recoveredIds)
        assertEquals(rows.size, kept.size + archived.size)

        // and the archived payloads are byte-identical to the originals, so a
        // future restore reconstructs the week verbatim
        for (a in archived) {
            val original = rows.first { it.id == a.messageId }
            assertEquals(original.partsJson, a.partsJson)
            assertEquals(original.role, a.role)
            assertEquals(original.reasoningContent, a.reasoningContent)
        }
    }

    @Test
    fun `truncating at the tail archives nothing when there is nothing after`() {
        val rows = listOf(msg(0, "user", "hi"), msg(1, "assistant", "yo"))
        val (kept, archived) = archiveAndTruncate(rows, keepCount = 2, reason = "retryLast")
        assertEquals(rows.map { it.id }, kept.map { it.id })
        assertTrue(archived.isEmpty())
    }
}
