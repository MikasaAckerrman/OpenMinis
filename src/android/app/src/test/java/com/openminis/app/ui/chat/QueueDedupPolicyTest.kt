package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B-dedup-acceptor] Scenario B acceptance (decision level) from
 * REVIEWS/evidence/20261104/queue-acceptance-spec.md: a persisted-queue
 * entry whose promptId is already durable in the DB is split out for
 * dropping; entries without a durable marker survive untouched, in FIFO
 * order. The split NEVER invents a drop on its own — an empty accepted set
 * (the caller's fail-open value on any DB doubt) leaves everything fresh.
 */
class QueueDedupPolicyTest {

    private fun entry(id: String, text: String = "t-$id") =
        QueuedPrompt(id = id, text = text)

    @Test
    fun `empty accepted set - everything stays fresh (fail-open)`() {
        val entries = listOf(entry("a"), entry("b"), entry("c"))
        val split = QueueDedupPolicy.split(entries, acceptedIds = emptySet())
        assertEquals(entries, split.fresh)
        assertTrue(split.alreadyAccepted.isEmpty())
    }

    @Test
    fun `partially accepted - stale split out, fresh keeps FIFO order`() {
        val entries = listOf(entry("a"), entry("b"), entry("c"), entry("d"))
        val split = QueueDedupPolicy.split(entries, acceptedIds = setOf("b", "d"))
        assertEquals(listOf("a", "c"), split.fresh.map { it.id })
        assertEquals(listOf("b", "d"), split.alreadyAccepted.map { it.id })
    }

    @Test
    fun `all accepted - nothing fresh (B-window restore drops the whole stale queue)`() {
        val entries = listOf(entry("a"), entry("b"))
        val split = QueueDedupPolicy.split(entries, acceptedIds = setOf("a", "b"))
        assertTrue(split.fresh.isEmpty())
        assertEquals(entries, split.alreadyAccepted)
    }

    @Test
    fun `empty queue - no-op split`() {
        val split = QueueDedupPolicy.split(emptyList(), acceptedIds = setOf("a"))
        assertTrue(split.fresh.isEmpty())
        assertTrue(split.alreadyAccepted.isEmpty())
    }
}
