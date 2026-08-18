package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-session-independence] Proves the eviction rule that keeps N actively
 * working sessions independent. RED against the old flat-cap behaviour where
 * a session with queued/awaiting work was evictable the moment a 4th owner
 * touched the store.
 */
class ResidentEvictionPolicyTest {

    // ---- hasLiveWork: the definition of "irreplaceable" -----------------

    @Test
    fun `idle session has no live work`() {
        assertFalse(
            ResidentEvictionPolicy.hasLiveWork(
                isStreaming = false, hasQueuedPrompts = false, isCompacting = false,
            ),
        )
    }

    @Test
    fun `streaming counts as live work`() {
        assertTrue(
            ResidentEvictionPolicy.hasLiveWork(
                isStreaming = true, hasQueuedPrompts = false, isCompacting = false,
            ),
        )
    }

    @Test
    fun `a queued prompt counts as live work even when not streaming yet`() {
        // The exact gap the old rule missed: a session with a prompt waiting to
        // drain is NOT streaming this instant, but cancelling its scope loses
        // that prompt. Must be protected.
        assertTrue(
            ResidentEvictionPolicy.hasLiveWork(
                isStreaming = false, hasQueuedPrompts = true, isCompacting = false,
            ),
        )
    }

    @Test
    fun `in-flight compaction counts as live work`() {
        assertTrue(
            ResidentEvictionPolicy.hasLiveWork(
                isStreaming = false, hasQueuedPrompts = false, isCompacting = true,
            ),
        )
    }

    // ---- keysToEvict: protected sessions are never dropped --------------

    @Test
    fun `three working sessions plus one draft owner evicts nothing`() {
        // The reported scenario: user drives 3 sessions, all with live work,
        // then a 4th owner (draft / list / preview) touches the store. Old
        // behaviour: one of the 3 gets evicted (scope cancelled). New: all 3
        // are protected, so nothing is evicted.
        val lru = listOf("s1", "s2", "s3", "draft")
        val protectedKeys = setOf("s1", "s2", "s3") // all have live work
        val evict = ResidentEvictionPolicy.keysToEvict(
            lruOrder = lru,
            protectedKeys = protectedKeys,
            maxResidentIdle = 3,
        )
        assertEquals(emptyList<String>(), evict)
    }

    @Test
    fun `protected sessions never counted against the idle bound`() {
        // 5 sessions, 3 protected (working) + 2 idle, bound = 3 idle. Even
        // though total (5) exceeds the bound, no idle overflow → evict nothing.
        val lru = listOf("work1", "idleA", "work2", "idleB", "work3")
        val protectedKeys = setOf("work1", "work2", "work3")
        val evict = ResidentEvictionPolicy.keysToEvict(
            lruOrder = lru,
            protectedKeys = protectedKeys,
            maxResidentIdle = 3,
        )
        assertEquals(emptyList<String>(), evict)
    }

    @Test
    fun `only idle overflow is evicted oldest first`() {
        // 5 idle sessions, none protected, bound = 3 → drop the 2 oldest.
        val lru = listOf("old1", "old2", "mid", "new1", "new2")
        val evict = ResidentEvictionPolicy.keysToEvict(
            lruOrder = lru,
            protectedKeys = emptySet(),
            maxResidentIdle = 3,
        )
        assertEquals(listOf("old1", "old2"), evict)
    }

    @Test
    fun `eviction skips protected keys and takes the next oldest idle`() {
        // oldest is protected → it stays; the eviction falls through to the
        // next-oldest IDLE key instead.
        val lru = listOf("protectedOld", "idle1", "idle2", "idle3")
        val evict = ResidentEvictionPolicy.keysToEvict(
            lruOrder = lru,
            protectedKeys = setOf("protectedOld"),
            maxResidentIdle = 2,
        )
        // idle set = [idle1, idle2, idle3], bound 2 → overflow 1 → drop idle1.
        assertEquals(listOf("idle1"), evict)
    }

    @Test
    fun `within bounds evicts nothing`() {
        val lru = listOf("a", "b")
        val evict = ResidentEvictionPolicy.keysToEvict(
            lruOrder = lru,
            protectedKeys = emptySet(),
            maxResidentIdle = 3,
        )
        assertEquals(emptyList<String>(), evict)
    }

    @Test
    fun `all sessions protected evicts nothing regardless of count`() {
        val lru = listOf("a", "b", "c", "d", "e", "f")
        val evict = ResidentEvictionPolicy.keysToEvict(
            lruOrder = lru,
            protectedKeys = lru.toSet(),
            maxResidentIdle = 1,
        )
        assertEquals(emptyList<String>(), evict)
    }
}
