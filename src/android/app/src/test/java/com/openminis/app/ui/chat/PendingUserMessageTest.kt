package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingUserMessageTest {

    @Test
    fun `fresh id is a unique pending placeholder`() {
        val a = PendingUserMessage.newId()
        val b = PendingUserMessage.newId()
        assertTrue(PendingUserMessage.isPending(a))
        assertTrue(a.startsWith(PendingUserMessage.ID_PREFIX))
        assertFalse(PendingUserMessage.isPending("real-123"))
        assertTrue(a != b)
    }

    @Test
    fun `reconcile swaps placeholder in place and preserves order`() {
        val pending = PendingUserMessage.newId()
        val before = listOf("a", pending, "b")
        assertEquals(listOf("a", "real", "b"), PendingUserMessage.reconcile(before, pending, "real"))
    }

    @Test
    fun `reconcile is a no-op when placeholder absent`() {
        val pending = PendingUserMessage.newId()
        val absent = listOf("x", "y")
        assertSame(absent, PendingUserMessage.reconcile(absent, pending, "real"))
        assertFalse(PendingUserMessage.contains(absent, pending))
    }

    @Test
    fun `drop removes only the placeholder`() {
        val pending = PendingUserMessage.newId()
        assertEquals(listOf("a", "b"), PendingUserMessage.dropPending(listOf("a", pending, "b"), pending))
        val absent = listOf("x", "y")
        assertEquals(absent, PendingUserMessage.dropPending(absent, pending))
    }
}
