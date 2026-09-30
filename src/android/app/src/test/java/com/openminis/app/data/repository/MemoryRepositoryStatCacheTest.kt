package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-memory-stat-cache] The keyword scan now checks pre-lowercased lines
 * per-line instead of building a joined+lowercased 5-line window string per
 * line. Keywords are whitespace-split (single words), so they cannot
 * straddle line boundaries — the semantics must be identical. Also covers
 * stat-cache invalidation: a file change must be visible on the next read.
 */
class MemoryRepositoryStatCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo() = MemoryRepository(tmp.root)

    @Test
    fun `keywords in same window match across lines`() {
        val f = tmp.newFile("2026-09-30.md")
        f.writeText(
            """
            intro line zero
            alpha is here
            plain filler
            beta sits nearby
            outro tail
            """.trimIndent(),
        )
        // alpha (line 1) and beta (line 3) are within the ±2 window of
        // each other — both keywords must match as one range.
        val r = repo().getMemory("alpha beta", scope = "all")
        assertTrue(r.isNotBlank())
        assertFalse(r.startsWith("No memory"))
        assertTrue(r.contains("alpha"))
    }

    @Test
    fun `keywords far apart do not match`() {
        val f = tmp.newFile("2026-09-30.md")
        val far = "gamma at top\n" + ("filler\n".repeat(20)) + "delta far below\n"
        f.writeText(far)
        val r = repo().getMemory("gamma delta", scope = "all")
        // Windows never contain both words → no match.
        assertTrue(r.isBlank() || r.startsWith("No memory"))
    }

    @Test
    fun `case insensitive match`() {
        val f = tmp.newFile("2026-09-30.md")
        f.writeText("The QUICK brown FOX\n")
        val r = repo().getMemory("quick fox", scope = "all")
        assertTrue(r.isNotBlank() && !r.startsWith("No memory"))
    }

    @Test
    fun `stat cache invalidates on file change`() {
        val f = tmp.newFile("2026-09-30.md")
        f.writeText("original keyword-one\n")
        val repo = repo()
        val first = repo.getMemory("keyword-one", scope = "all")
        assertTrue(first.isNotBlank() && !first.startsWith("No memory"))
        // Rewrite the file — length/mtime move → cache must miss.
        f.writeText("replaced keyword-two completely different size padding\n\n\n")
        val second = repo.getMemory("keyword-two", scope = "all")
        assertTrue(second.isNotBlank() && !second.startsWith("No memory"))
        val gone = repo.getMemory("keyword-one", scope = "all")
        assertTrue(gone.isBlank() || gone.startsWith("No memory"))
    }

    @Test
    fun `daily fragment reflects file growth`() {
        val f = tmp.newFile("2026-09-30.md")
        val repo = repo()
        f.writeText("day one line\n")
        val a = repo.loadRecentDailyMemoryFragment(2000) ?: ""
        assertTrue(a.contains("day one"))
        f.writeText("day one line\nday one line grew\n")
        val b = repo.loadRecentDailyMemoryFragment(2000) ?: ""
        assertTrue(b.contains("grew"))
    }
}
