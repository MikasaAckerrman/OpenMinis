package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-selection-gaps] Document order for every shard-id shape the app
 * actually produces.
 *
 * Why this matters more than "sorting": `crossShardSelectedText` skips shards
 * until it meets the first endpoint and BREAKS at the last one. A wrong order
 * therefore does not produce mis-ordered text — it produces MISSING text. That is
 * the reported symptom: parts of a long selection simply did not copy.
 */
class ShardOrderTest {

    private fun id(shard: String, message: String = "m1") = TextShardId(message, shard)

    // ── shapes the app produces ─────────────────────────────────────────────

    @Test
    fun `markdown fragment ids order by block then sibling index`() {
        assertTrue(shardOrderKey(id("mdblock:p:3#0"))!! < shardOrderKey(id("mdblock:p:3#1"))!!)
        assertTrue(shardOrderKey(id("mdblock:p:3#9"))!! < shardOrderKey(id("mdblock:p:4#0"))!!)
        assertEquals(3_000_002L, shardOrderKey(id("mdblock:p:3#2")))
    }

    @Test
    fun `text block ids are ordered — the regression`() {
        // ChatScreen builds "text:<blockId>" and MdText appends "#<sub>". blockId
        // is NOT numeric, so the old parser returned null for all of them, and
        // ordering fell through to on-screen position — unavailable for recycled
        // rows. Every sibling text node of an AssistantText block was affected.
        val a = shardOrderKey(id("text:blk-a1b2#0"))
        val b = shardOrderKey(id("text:blk-a1b2#1"))
        assertTrue("text: ids must be orderable", a != null && b != null)
        assertTrue(a!! < b!!)
    }

    @Test
    fun `legacy ids are ordered — the other half of the regression`() {
        // "legacy#<sub>" has no colon at all, so the old parser bailed before the
        // number parse. Pre-migration sessions are exactly the long ones.
        val a = shardOrderKey(id("legacy#0"))
        val b = shardOrderKey(id("legacy#2"))
        assertTrue(a != null && b != null)
        assertTrue(a!! < b!!)
    }

    @Test
    fun `an id with no sub-index sorts before its siblings`() {
        // Non-chat callers render a single MdText with no allocator in scope, so
        // the bare base id must still be orderable.
        assertEquals(0L, shardOrderKey(id("legacy")))
        assertTrue(shardOrderKey(id("legacy"))!! < shardOrderKey(id("legacy#1"))!!)
    }

    @Test
    fun `unknown shapes stay unordered rather than guessing`() {
        // Inventing an order for an id we do not recognize would silently
        // truncate a copy; null routes the caller to the visual fallback.
        assertNull(shardOrderKey(id("something-else")))
        assertNull(shardOrderKey(id("mdblock:p:notanumber")))
    }

    @Test
    fun `a malformed sub-index degrades to zero, not to unordered`() {
        assertEquals(3_000_000L, shardOrderKey(id("mdblock:p:3#oops")))
    }

    // ── comparability: when the key may be used at all ──────────────────────

    @Test
    fun `two fragments of the same parent are comparable`() {
        assertTrue(
            ShardOrder.comparableByKey(listOf(id("mdblock:p:1#0"), id("mdblock:p:2#0"))),
        )
    }

    @Test
    fun `fragments of DIFFERENT parents are not comparable by key`() {
        // Both parents number their fragments from 0, so parent B's fragment 0
        // would compare as "before" parent A's fragment 1 — a false order, and a
        // false order drops text.
        assertFalse(
            ShardOrder.comparableByKey(listOf(id("mdblock:pA:1#0"), id("mdblock:pB:0#0"))),
        )
    }

    @Test
    fun `two different text blocks are not comparable by key`() {
        // Every "text:" id reports blockIndex 0; only subIndex differs, and that
        // is meaningless across blocks.
        assertFalse(
            ShardOrder.comparableByKey(listOf(id("text:blkA#0"), id("text:blkB#0"))),
        )
        assertTrue(
            ShardOrder.comparableByKey(listOf(id("text:blkA#0"), id("text:blkA#3"))),
        )
    }

    @Test
    fun `shards from different messages are not comparable by key`() {
        assertFalse(
            ShardOrder.comparableByKey(
                listOf(id("mdblock:p:0#0", "m1"), id("mdblock:p:1#0", "m2")),
            ),
        )
    }

    @Test
    fun `a single shard and an empty set are trivially comparable`() {
        assertTrue(ShardOrder.comparableByKey(emptyList()))
        assertTrue(ShardOrder.comparableByKey(listOf(id("anything"))))
    }

    @Test
    fun `unknown shapes are not comparable even within one message`() {
        assertFalse(
            ShardOrder.comparableByKey(listOf(id("weird-a"), id("weird-b"))),
        )
    }

    // ── parsing details ─────────────────────────────────────────────────────

    @Test
    fun `parse identifies each family`() {
        assertEquals(ShardOrder.Family.MARKDOWN_BLOCK, ShardOrder.parse("mdblock:p:2#1")!!.family)
        assertEquals(ShardOrder.Family.TEXT_BLOCK, ShardOrder.parse("text:blk#1")!!.family)
        assertEquals(ShardOrder.Family.LEGACY, ShardOrder.parse("legacy#1")!!.family)
        assertEquals(ShardOrder.Family.UNKNOWN, ShardOrder.parse("nope")!!.family)
        assertNull(ShardOrder.parse(""))
    }

    @Test
    fun `fragmentOwner strips the fragment index for markdown blocks only`() {
        assertEquals("mdblock:parent-7", ShardOrder.fragmentOwner(id("mdblock:parent-7:3#2")))
        assertEquals("text:blk-1", ShardOrder.fragmentOwner(id("text:blk-1#2")))
        assertEquals("legacy", ShardOrder.fragmentOwner(id("legacy#2")))
    }

    @Test
    fun `sameFragment ignores the sub-index and respects the message`() {
        assertTrue(ShardOrder.sameFragment(id("mdblock:p:3#0"), id("mdblock:p:3#5")))
        assertFalse(ShardOrder.sameFragment(id("mdblock:p:3#0"), id("mdblock:p:4#0")))
        assertFalse(
            ShardOrder.sameFragment(id("legacy#0", "m1"), id("legacy#0", "m2")),
        )
    }

    // ── the walk itself ─────────────────────────────────────────────────────

    /**
     * Model of `crossShardSelectedText`: skip until `first`, slice it, append
     * whole shards, slice `last`, stop. Proves that order errors delete text
     * instead of reordering it.
     */
    private fun walk(
        order: List<String>,
        first: String,
        last: String,
        firstOffset: Int,
        lastOffset: Int,
        texts: Map<String, String>,
    ): String {
        val sb = StringBuilder()
        var started = false
        for (key in order) {
            val txt = texts[key] ?: continue
            when (key) {
                first -> {
                    sb.append(txt.substring(firstOffset))
                    started = true
                }
                last -> {
                    if (started) sb.append('\n')
                    sb.append(txt.substring(0, lastOffset))
                    started = true
                    break
                }
                else -> if (started) sb.append('\n').append(txt)
            }
        }
        return sb.toString()
    }

    @Test
    fun `a wrong order silently truncates the copy`() {
        val texts = mapOf(
            "legacy#0" to "AAAA", "legacy#1" to "BBBB",
            "legacy#2" to "CCCC", "legacy#3" to "DDDD",
        )
        val correct = listOf("legacy#0", "legacy#1", "legacy#2", "legacy#3")
        assertEquals(
            "AAA\nBBBB\nCCCC\nDDD",
            walk(correct, "legacy#0", "legacy#3", 1, 3, texts),
        )
        // Reversed: the walk meets the LAST endpoint first and breaks immediately.
        assertEquals(
            "DDD",
            walk(correct.reversed(), "legacy#0", "legacy#3", 1, 3, texts),
        )
    }

    @Test
    fun `sorting legacy ids by the new key produces the correct walk`() {
        val texts = mapOf(
            "legacy#0" to "AAAA", "legacy#1" to "BBBB",
            "legacy#2" to "CCCC", "legacy#3" to "DDDD",
        )
        // Feed them in a scrambled order, as an unordered map iteration would.
        val scrambled = listOf("legacy#2", "legacy#0", "legacy#3", "legacy#1")
        val sorted = scrambled.map { id(it) }
            .sortedBy { shardOrderKey(it) }
            .map { it.shardId }
        assertEquals(
            "AAA\nBBBB\nCCCC\nDDD",
            walk(sorted, "legacy#0", "legacy#3", 1, 3, texts),
        )
    }
}
