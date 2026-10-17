package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CompactDiskCache contract: window summaries must survive a process kill —
 * a rate-limited compact interrupted at window 9/13 must resume for free.
 */
class CompactDiskCacheTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `round trip persists entries across store-load`() {
        val f = CompactDiskCache.file(tmp.root, "session-abc")
        val map = CompactDiskCache.load(f)
        CompactDiskCache.put(f, map, "L0.4 #1001", "done-window-1")
        CompactDiskCache.put(f, map, "L0.4 #1002", "done-window-2")

        val reloaded = CompactDiskCache.load(f)
        assertEquals("done-window-1", reloaded["L0.4 #1001"])
        assertEquals("done-window-2", reloaded["L0.4 #1002"])
        assertEquals(
            listOf("L0.4 #1001", "L0.4 #1002"),
            reloaded.keys.toList(),
        )
    }

    @Test
    fun `lru cap keeps only the newest entries`() {
        val f = CompactDiskCache.file(tmp.root, "s1")
        val map = CompactDiskCache.load(f)
        repeat(CompactDiskCache.MAX_ENTRIES + 40) { i ->
            CompactDiskCache.put(f, map, "key$i", "v$i")
        }
        val reloaded = CompactDiskCache.load(f)
        assertEquals(CompactDiskCache.MAX_ENTRIES, reloaded.size)
        assertFalse("oldest evicted", reloaded.containsKey("key0"))
        assertTrue(
            "newest kept",
            reloaded.containsKey("key${CompactDiskCache.MAX_ENTRIES + 39}"),
        )
    }

    @Test
    fun `corrupted or missing store degrades to empty`() {
        val f = File(File(tmp.root, "compact-cache"), "garbage.json")
        f.parentFile?.mkdirs()
        f.writeText("not json {{{ definitely")
        val reloaded = CompactDiskCache.load(f)
        assertTrue(reloaded.isEmpty())

        val missing = File(tmp.root, "compact-cache/nope.json")
        assertTrue(CompactDiskCache.load(missing).isEmpty())
    }

    @Test
    fun `filename sanitized and dot-json`() {
        val f = CompactDiskCache.file(tmp.root, "ses/si:on?*")
        assertTrue(f.name.endsWith(".json"))
        assertFalse(f.name.contains("/") || f.name.contains(":") || f.name.contains("?") || f.name.contains("*"))
    }
}
