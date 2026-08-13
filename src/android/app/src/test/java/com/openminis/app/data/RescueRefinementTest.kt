package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-session-rescue-refine] The verifier is the whole reason the LLM stage is
 * safe to enable by default: an unverified rewrite would paraphrase paths and
 * drop hashes, destroying exactly what the digest exists to carry. These tests
 * pin the accept/reject matrix.
 */
class RescueRefinementTest {

    private val sha = "b212fbc56c51520c8082c9d1254fee170250f847bef4a6cc80a0177fe89610d0"
    private val path = "/var/minis/shared/openminis/apk/OpenMinis-clone.apk"
    private val url = "https://github.com/acme/repo/actions/runs/31686665368"

    private fun digest(padding: Int = 3_000): String =
        RescueDigest.OPEN_TAG + "\n" +
            "User asked to build the APK and verify it.\n" +
            "Paths (verbatim): $path\n" +
            "URLs: $url\n" +
            "Hashes / commits: $sha\n" +
            "filler ".repeat(padding / 7) +
            "\n</rescue-digest>"

    @Test
    fun `accepts a shorter rewrite that keeps every fact`() {
        val d = digest()
        val refined = "Built and verified the APK.\nFacts: $path, $url, $sha\n" +
            "detail ".repeat(60)
        val verdict = RescueRefinement.verify(d, refined, maxChars = 12_000)
        assertTrue(
            "expected Accepted, got $verdict",
            verdict is RescueRefinement.Verdict.Accepted,
        )
        val text = (verdict as RescueRefinement.Verdict.Accepted).text
        assertTrue(text.startsWith(RescueDigest.OPEN_TAG))
        assertTrue(text.contains(sha))
    }

    @Test
    fun `rejects a rewrite that paraphrases a path`() {
        val d = digest()
        val refined = "Built the APK in the shared folder and verified its checksum.\n" +
            "See the CI run for details.\n" + "detail ".repeat(60)
        val verdict = RescueRefinement.verify(d, refined, maxChars = 12_000)
        assertTrue(verdict is RescueRefinement.Verdict.Rejected)
        assertTrue(
            (verdict as RescueRefinement.Verdict.Rejected).reason.contains("verbatim fact"),
        )
    }

    @Test
    fun `rejects a truncated hash`() {
        val d = digest()
        val refined = "Built the APK.\nFacts: $path, $url, ${sha.take(12)}…\n" +
            "detail ".repeat(60)
        assertTrue(
            RescueRefinement.verify(d, refined, 12_000) is RescueRefinement.Verdict.Rejected,
        )
    }

    @Test
    fun `rejects empty and blank output`() {
        val d = digest()
        for (bad in listOf("", "   ", "\n\n")) {
            val v = RescueRefinement.verify(d, bad, 12_000)
            assertTrue(v is RescueRefinement.Verdict.Rejected)
            assertEquals("empty", (v as RescueRefinement.Verdict.Rejected).reason)
        }
    }

    @Test
    fun `rejects output that is not meaningfully shorter`() {
        val d = digest()
        // Same facts, same length — no reason to swap a deterministic artifact
        // for a generated one.
        val refined = d.replace(RescueDigest.OPEN_TAG, "Summary:")
        val v = RescueRefinement.verify(d, refined, 12_000)
        assertTrue(v is RescueRefinement.Verdict.Rejected)
        assertTrue((v as RescueRefinement.Verdict.Rejected).reason.contains("shrink"))
    }

    @Test
    fun `rejects output that blows the budget`() {
        val d = digest(padding = 200)
        val refined = "$path $url $sha " + "x".repeat(20_000)
        val v = RescueRefinement.verify(d, refined, maxChars = 5_000)
        assertTrue(v is RescueRefinement.Verdict.Rejected)
        assertTrue((v as RescueRefinement.Verdict.Rejected).reason.contains("too long"))
    }

    @Test
    fun `rejects suspiciously short output even when facts survive`() {
        val d = digest(padding = 6_000)
        // Facts present but everything else gone — that is fact-listing, not
        // summarizing, and it means the narrative was lost.
        val refined = "$path $url $sha"
        val v = RescueRefinement.verify(d, refined, 12_000)
        assertTrue(v is RescueRefinement.Verdict.Rejected)
        assertTrue((v as RescueRefinement.Verdict.Rejected).reason.contains("suspiciously short"))
    }

    @Test
    fun `rejects chatter and refusals`() {
        val d = digest()
        val bodies = listOf(
            "Sure, here's the rewritten summary: $path $url $sha " + "d ".repeat(200),
            "I cannot summarize this content. $path $url $sha " + "d ".repeat(200),
            "As an AI language model I have rewritten it: $path $url $sha " + "d ".repeat(200),
        )
        for (b in bodies) {
            val v = RescueRefinement.verify(d, b, 12_000)
            assertTrue("expected rejection for: ${b.take(40)}", v is RescueRefinement.Verdict.Rejected)
        }
    }

    @Test
    fun `verbatimFacts ignores short ambiguous tokens`() {
        val facts = RescueRefinement.verbatimFacts(
            "see /a/b and $path plus deadbeef and $sha",
        )
        assertTrue(facts.contains(path))
        assertTrue(facts.contains(sha))
        // "/a/b" is 4 chars — too short to demand back without false positives.
        assertTrue(facts.none { it == "/a/b" })
        // 8-char hex could be an English word fragment; only >=12 counts.
        assertTrue(facts.none { it == "deadbeef" })
    }

    @Test
    fun `verbatimFacts checklist is capped`() {
        val many = (1..200).joinToString(" ") { "/var/minis/workspace/file_number_$it.txt" }
        assertTrue(RescueRefinement.verbatimFacts(many).size <= 40)
    }

    @Test
    fun `already-tagged output is not double-wrapped`() {
        val d = digest()
        val refined = RescueDigest.OPEN_TAG + "\nShort summary. $path $url $sha\n" +
            "detail ".repeat(60) + "\n</rescue-digest>"
        val v = RescueRefinement.verify(d, refined, 12_000)
        assertTrue(v is RescueRefinement.Verdict.Accepted)
        val text = (v as RescueRefinement.Verdict.Accepted).text
        assertEquals(1, Regex(Regex.escape(RescueDigest.OPEN_TAG)).findAll(text).count())
    }

    @Test
    fun `accepted output always carries the rescue tag`() {
        // The tag is what makes effectiveAgentHistory suppress the verbatim
        // warm-up; losing it on refinement would silently re-break the session.
        val d = digest()
        val refined = "Plain summary without a tag. $path $url $sha " + "detail ".repeat(60)
        val v = RescueRefinement.verify(d, refined, 12_000)
        val text = (v as RescueRefinement.Verdict.Accepted).text
        assertTrue(text.startsWith(RescueDigest.OPEN_TAG))
        assertTrue(text.endsWith(RescueRefinement.CLOSE_TAG))
    }
}
