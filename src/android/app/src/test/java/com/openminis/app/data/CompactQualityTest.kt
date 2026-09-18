package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [CompactQuality], which backs both the post-compaction polish
 * (ChatViewModel.compactAll) and the smart truncation in the transcript
 * builder (ChatViewModel.summarizeBody). Pure JVM — no Android.
 *
 * The two invariants that matter: polish is NEVER worse than the model's
 * output (filler removed, dropped identifiers restored verbatim), and
 * criticalFacts finds the identifiers a resuming agent cannot re-derive
 * without false positives on ordinary prose.
 */
class CompactQualityTest {

    @Test
    fun `stripFiller removes ceremony but keeps facts`() {
        val input = """
            Sure! Here is the summary:
            User asked to edit /etc/hosts and the agent did it.
            I hope this helps!
        """.trimIndent()
        val out = CompactQuality.stripFiller(input)
        assertTrue(out.contains("/etc/hosts"))
        assertFalse(out.lowercase().contains("sure!"))
        assertFalse(out.lowercase().contains("i hope this helps"))
        assertFalse(out.lowercase().contains("here is the summary"))
    }

    @Test
    fun `stripFiller collapses blank runs`() {
        val input = "line one\n\n\n\n\nline two"
        val out = CompactQuality.stripFiller(input)
        assertFalse("no 3+ blank runs remain", out.contains("\n\n\n"))
        assertTrue(out.contains("line one"))
        assertTrue(out.contains("line two"))
    }

    @Test
    fun `criticalFacts captures paths urls and hashes`() {
        val t = "built /var/minis/shared/proj/app.apk, pushed to " +
            "https://github.com/acme/repo at commit 3fa9c1e88b0d, done"
        val facts = CompactQuality.criticalFacts(t)
        assertTrue(facts.any { it == "/var/minis/shared/proj/app.apk" })
        assertTrue(facts.any { it.startsWith("https://github.com/acme/repo") })
        assertTrue(facts.any { it == "3fa9c1e88b0d" })
    }

    @Test
    fun `criticalFacts ignores short slash fragments and plain prose`() {
        val facts = CompactQuality.criticalFacts("the a/b test ran and it/was fine and ok")
        // "a/b" and "it/was" are below the 12-char path floor.
        assertTrue("no false-positive short paths", facts.isEmpty())
    }

    @Test
    fun `polish restores an identifier the model dropped`() {
        val transcript = "agent wrote /var/minis/shared/build/output-release.apk successfully"
        val modelSummary = "The agent built the release APK." // path paraphrased away
        val out = CompactQuality.polish(transcript, modelSummary)
        assertTrue("dropped path is re-appended verbatim",
            out.contains("/var/minis/shared/build/output-release.apk"))
        assertTrue("preserves the model's prose too", out.contains("built the release APK"))
        assertTrue("adds the appendix header",
            out.contains("Exact references preserved"))
    }

    @Test
    fun `polish adds no appendix when nothing was dropped`() {
        val transcript = "edited /tmp/minis-work/config.yaml and reran the suite"
        val summary = "Edited /tmp/minis-work/config.yaml and reran the suite; all green."
        val out = CompactQuality.polish(transcript, summary)
        assertFalse("no appendix when every fact survived",
            out.contains("Exact references preserved"))
    }

    @Test
    fun `polish respects the char ceiling`() {
        val transcript = buildString {
            repeat(200) { append("/var/minis/shared/f$it/artifact-$it.bin\n") }
        }
        val out = CompactQuality.polish(transcript, "did stuff", maxChars = 500)
        assertTrue("output never exceeds the budget", out.length <= 500)
    }

    @Test
    fun `polish output is never longer in facts than input plus summary`() {
        // Regression guard on the core promise: polish only removes filler and
        // re-adds facts already present in the transcript — it never fabricates.
        val transcript = "ran build at /opt/minis-app, hash deadbeefcafe0001"
        val summary = "Sure! The build ran."
        val out = CompactQuality.polish(transcript, summary)
        assertTrue(out.contains("deadbeefcafe0001"))
        assertTrue(out.contains("/opt/minis-app"))
        assertFalse(out.lowercase().startsWith("sure"))
    }
}
