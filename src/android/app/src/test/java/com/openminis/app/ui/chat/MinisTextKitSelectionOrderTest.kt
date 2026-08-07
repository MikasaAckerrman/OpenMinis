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
    fun `legacy and nonnumeric ids use visual fallback`() {
        assertNull(shardOrderKey(id("legacy")))
        assertNull(shardOrderKey(id("text:block-id")))
    }

    @Test
    fun `message id does not affect local shard order key`() {
        assertEquals(
            shardOrderKey(TextShardId("message-1", "mdblock:p:2#4")),
            shardOrderKey(TextShardId("message-2", "mdblock:p:2#4")),
        )
    }
}
