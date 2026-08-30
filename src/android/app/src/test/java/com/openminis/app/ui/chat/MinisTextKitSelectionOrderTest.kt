package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression tests for selection order after LazyColumn recycling. */
class MinisTextKitSelectionOrderTest {

    private fun id(value: String) = TextShardId("message-1", value)

    @Test
    fun `mdblock suffix with subindex keeps block order`() {
        assertEquals(3_000_002L, shardOrderKey(id("mdblock:p:3#2")))
        assertEquals(12_000_000L, shardOrderKey(id("mdblock:p:12")))
    }

    @Test
    fun `subindex orders siblings inside one markdown block`() {
        val first = shardOrderKey(id("mdblock:p:3#0"))!!
        val second = shardOrderKey(id("mdblock:p:3#1"))!!
        assertEquals(true, first < second)
    }

    @Test
    fun `single-fragment shapes are ordered, not left to visual fallback`() {
        // [T-android-selection-gaps] This test used to assert null here, which
        // codified the bug: "text:<blockId>" and "legacy" carry a #subIndex
        // assigned in composition order, and refusing to read it sent ordering
        // to on-screen position — unavailable for recycled rows, so long copies
        // silently lost text. See ShardOrderTest for the full shape matrix.
        assertEquals(0L, shardOrderKey(id("legacy")))
        assertEquals(0L, shardOrderKey(id("text:block-id")))
        assertEquals(2L, shardOrderKey(id("legacy#2")))
        // Genuinely unknown shapes still fall back, on purpose.
        assertNull(shardOrderKey(id("no-known-shape")))
    }

    @Test
    fun `message id does not affect local shard order key`() {
        assertEquals(
            shardOrderKey(TextShardId("message-1", "mdblock:p:2#4")),
            shardOrderKey(TextShardId("message-2", "mdblock:p:2#4")),
        )
    }
}
