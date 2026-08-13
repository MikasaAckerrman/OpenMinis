package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-memory-inject-budget] Tests for the char-budgeted daily-log injection.
 * The 200-line cap alone let ~90K chars (~25K tokens) into every system
 * prompt on dense work logs; the fragment is now capped per file AND in
 * total, cutting at line boundaries, with an explicit omission note.
 */
class MemoryRepositoryInjectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo() = MemoryRepository(tmp.root)

    private fun dateStr(daysAgo: Int): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return fmt.format(Date(System.currentTimeMillis() - daysAgo * 86_400_000L))
    }

    /** Write a daily log of [lines] lines, each exactly [lineLen] chars. */
    private fun writeLog(daysAgo: Int, lines: Int, lineLen: Int): Int {
        val line = "x".repeat(lineLen)
        val content = (1..lines).joinToString("\n") { line }
        tmp.newFile("${dateStr(daysAgo)}.md").writeText(content)
        return content.length
    }

    @Test
    fun `small logs pass through untouched`() {
        writeLog(daysAgo = 1, lines = 10, lineLen = 40)
        val fragment = repo().loadRecentDailyMemoryFragment()
        assertNotNull(fragment)
        assertTrue(fragment!!.contains("x".repeat(40)))
        assertFalse(fragment.contains("more lines"))
        assertFalse(fragment.contains("omitted"))
    }

    @Test
    fun `per-file char cap cuts at a line boundary`() {
        // 200 lines x 40 chars = 8000 chars > 3000 per-file cap.
        writeLog(daysAgo = 1, lines = 200, lineLen = 40)
        val fragment = repo().loadRecentDailyMemoryFragment()!!
        // 3000 / 41 ≈ 73 lines fit; every kept line must be whole.
        val preview = fragment.substringAfter("daily log (${dateStr(1)}.md):\n")
            .substringBefore("\n...")
        assertTrue(preview.length <= MemoryRepository.MAX_INJECT_CHARS_PER_FILE)
        assertTrue(preview.lines().all { it == "x".repeat(40) })
        assertTrue(fragment.contains("more lines, use memory_get to search"))
    }

    @Test
    fun `total budget bounds the whole fragment`() {
        // Three dense days: 3 x 8000 chars. Total budget 8000 must bound
        // the injected previews (plus labels/markers overhead).
        writeLog(daysAgo = 1, lines = 200, lineLen = 40)
        writeLog(daysAgo = 2, lines = 200, lineLen = 40)
        writeLog(daysAgo = 3, lines = 200, lineLen = 40)
        val budget = 8_000
        val fragment = repo().loadRecentDailyMemoryFragment(maxTotalChars = budget)!!
        // Labels + markers + the static intro exist, but previews+entries
        // stay near the budget: hard ceiling = budget + per-file slack.
        assertTrue(
            "fragment body should be bounded, was ${fragment.length}",
            fragment.length <= budget + 3_500, // intro text + labels + markers
        )
    }

    @Test
    fun `budget starvation omits older files with a note`() {
        // Day 1 eats nearly the whole budget (entry ≈3078 chars); the
        // remaining ~220 chars are below MIN_USEFUL_FILE_CHARS, so day 2
        // must be skipped entirely and the omission note shown.
        // NB: the entry label renders as "daily log (FILE.md):" — the
        // closing paren sits before the colon, hence the ".md):" needle.
        writeLog(daysAgo = 1, lines = 200, lineLen = 40)
        writeLog(daysAgo = 2, lines = 10, lineLen = 40)
        val fragment = repo().loadRecentDailyMemoryFragment(maxTotalChars = 3_300)!!
        assertTrue(fragment.contains("${dateStr(1)}.md):"))
        assertFalse(fragment.contains("${dateStr(2)}.md):"))
        assertTrue(fragment.contains("omitted to stay within the context budget"))
    }

    @Test
    fun `newest files win the budget`() {
        writeLog(daysAgo = 1, lines = 50, lineLen = 40)
        writeLog(daysAgo = 2, lines = 50, lineLen = 40)
        writeLog(daysAgo = 3, lines = 50, lineLen = 40)
        // Budget fits day 1 (2049 chars) + part of day 2; day 3 omitted.
        val fragment = repo().loadRecentDailyMemoryFragment(maxTotalChars = 4_000)!!
        assertTrue(fragment.contains("${dateStr(1)}.md):"))
        assertTrue(fragment.contains("${dateStr(2)}.md):"))
        assertFalse(fragment.contains("${dateStr(3)}.md):"))
        assertTrue(fragment.contains("omitted to stay within the context budget"))
    }

    @Test
    fun `giant first line gets a hard cut instead of an empty entry`() {
        // One 10KB single-line entry: the line-boundary loop would keep
        // nothing, so the loader must fall back to a mid-line cut.
        tmp.newFile("${dateStr(1)}.md").writeText("y".repeat(10_000))
        val fragment = repo().loadRecentDailyMemoryFragment()!!
        assertTrue(fragment.contains("${dateStr(1)}.md):"))
        assertTrue(fragment.contains("truncated, use memory_get"))
        assertTrue(fragment.contains("y".repeat(500)))
    }

    @Test
    fun `empty memory dir yields null fragment`() {
        assertNull(repo().loadRecentDailyMemoryFragment())
    }

    @Test
    fun `line cap still applies below the char cap`() {
        // 500 tiny lines: 500 lines * 5 chars = 2500 chars < 3000 char cap,
        // but the 200-line cap must bite first.
        writeLog(daysAgo = 1, lines = 500, lineLen = 5)
        val fragment = repo().loadRecentDailyMemoryFragment()!!
        assertTrue(fragment.contains("300 more lines"))
    }
}
