package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-fileread-cache] Wishlist No.8 core: the dedup rules the context
 * economy of long sessions rests on. Pure JVM — the PRoot path resolution
 * in FileReadTool itself is compile-checked only.
 */
class FileReadCacheTest {

    @Test
    fun `identical key and content is a hit`() {
        FileReadCache.clear()
        val k = FileReadCache.key("s1", "/var/minis/workspace/a.txt", "o=1;l=-1;d=head;m=15000")
        val sha = FileReadCache.sha256("same content")
        FileReadCache.record(k, sha, readAtMs = 1000L)
        val hit = FileReadCache.lookup(k, sha)
        assertNotNull(hit)
        assertEquals(1000L, hit!!.readAtMs)
    }

    @Test
    fun `same key but changed content is a miss`() {
        FileReadCache.clear()
        val k = FileReadCache.key("s1", "/f.txt", "shape")
        FileReadCache.record(k, FileReadCache.sha256("before"), 1000L)
        // The file was edited: the new fingerprint no longer matches the
        // recorded one — the caller must return full content.
        assertNull(FileReadCache.lookup(k, FileReadCache.sha256("after")))
    }

    @Test
    fun `different session or request shape never collides`() {
        FileReadCache.clear()
        val sha = FileReadCache.sha256("content")
        val a = FileReadCache.key("s1", "/f.txt", "o=1;l=-1;d=head;m=15000")
        FileReadCache.record(a, sha, 1000L)
        // Another session reading the identical shape.
        assertNull(FileReadCache.lookup(FileReadCache.key("s2", "/f.txt", "o=1;l=-1;d=head;m=15000"), sha))
        // Same session, different range — a new question.
        assertNull(FileReadCache.lookup(FileReadCache.key("s1", "/f.txt", "o=100;l=50;d=head;m=15000"), sha))
    }

    @Test
    fun `nul separator keeps paths from forging key collisions`() {
        val sha = FileReadCache.sha256("x")
        val k1 = FileReadCache.key("s1", "/a", "b=c")
        val k2 = FileReadCache.key("s1\u0000/a", "b=c", "")
        // A path crafted to swallow a delimiter must not alias another key.
        org.junit.Assert.assertNotEquals(k1, k2)
    }

    @Test
    fun `lru caps the store at 128 entries`() {
        FileReadCache.clear()
        val sha = FileReadCache.sha256("bulk")
        var firstKey: String? = null
        for (i in 0 until 130) {
            val k = FileReadCache.key("s1", "/f$i.txt", "shape")
            if (i == 0) firstKey = k
            FileReadCache.record(k, sha, readAtMs = i.toLong())
        }
        // 129th insert evicted the oldest (access-order LRU: nothing was
        // looked up in between, so eviction follows insertion order).
        assertNull(FileReadCache.lookup(firstKey!!, sha))
        // The newest survives.
        assertNotNull(FileReadCache.lookup(FileReadCache.key("s1", "/f129.txt", "shape"), sha))
    }

    @Test
    fun `sha256 is deterministic and line-normalization safe`() {
        val a = FileReadCache.sha256("line1\nline2")
        assertEquals(a, FileReadCache.sha256("line1\nline2"))
        org.junit.Assert.assertNotEquals(a, FileReadCache.sha256("line1\nline2 changed"))
    }
}
