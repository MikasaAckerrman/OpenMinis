package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-context-maintenance] The cadence gate has to satisfy two things that pull
 * against each other: run often enough that a session never reaches the
 * unsendable state, and never spend a request on a session small enough that
 * summarising costs more than it saves.
 */
class ContextMaintenanceTest {

    private val now = 10_000_000L
    private val window = 200_000

    private fun decide(
        turns: Int,
        tokens: Int,
        window: Int = this.window,
        compactSupported: Boolean = true,
        isCompacting: Boolean = false,
        lastFullAtMs: Long = 0L,
        cadence: Int = ContextMaintenance.DEFAULT_FULL_EVERY_N_TURNS,
    ) = ContextMaintenance.decide(
        userTurnsSinceFull = turns,
        contextTokens = tokens,
        contextWindow = window,
        compactSupported = compactSupported,
        isCompacting = isCompacting,
        lastFullAtMs = lastFullAtMs,
        nowMs = now,
        fullEveryNTurns = cadence,
    )

    @Test
    fun `small session never pays for a full pass`() {
        // 10K of 200K = 5%. Even after 50 turns, summarising is pure waste.
        assertEquals(ContextMaintenance.Action.LIGHT, decide(turns = 50, tokens = 10_000))
    }

    @Test
    fun `light pass runs when pressure is unknown`() {
        assertEquals(ContextMaintenance.Action.LIGHT, decide(turns = 1, tokens = 0))
        assertEquals(ContextMaintenance.Action.LIGHT, decide(turns = 1, tokens = 5_000, window = 0))
    }

    @Test
    fun `full pass fires on cadence once past the pressure floor`() {
        val tokens = (window * 0.55).toInt() // above the 0.50 floor
        assertEquals(ContextMaintenance.Action.LIGHT, decide(turns = 7, tokens = tokens))
        assertEquals(ContextMaintenance.Action.FULL, decide(turns = 8, tokens = tokens))
    }

    @Test
    fun `heavy pressure does not wait for the cadence`() {
        // Halfway between floor (0.50) and ceiling (0.85) is 0.675.
        val tokens = (window * 0.70).toInt()
        assertEquals(ContextMaintenance.Action.FULL, decide(turns = 1, tokens = tokens))
    }

    @Test
    fun `past the ceiling it goes local instead of asking the model`() {
        val tokens = (window * 0.90).toInt()
        assertEquals(ContextMaintenance.Action.RESCUE, decide(turns = 0, tokens = tokens))
    }

    @Test
    fun `first rescue past the ceiling fires even during cooldown`() {
        // The very first time pressure crosses the ceiling, lastFullAtMs is
        // stale/zero, so rescue fires immediately — waiting is what produces
        // the unsendable state.
        val tokens = (window * 0.95).toInt()
        assertEquals(
            ContextMaintenance.Action.RESCUE,
            decide(turns = 0, tokens = tokens, lastFullAtMs = 0L),
        )
    }

    @Test
    fun `a rescue that just ran does not re-rescue every turn while still over the ceiling`() {
        // [T-compaction-loop] The endless-compaction loop: a session whose
        // pressure stays above the ceiling AFTER a rescue (the protected recent
        // tail + pinned content can't shrink below it) used to RESCUE on every
        // single send, writing a marker per turn. Now a rescue within the
        // cooldown yields to the free local pass; the UI's "start a new chat"
        // nudge takes over instead of an infinite fold.
        val tokens = (window * 0.95).toInt()
        assertEquals(
            ContextMaintenance.Action.LIGHT,
            decide(turns = 0, tokens = tokens, lastFullAtMs = now - 1_000),
        )
        // Once the cooldown expires, a still-oversized session may rescue again.
        assertEquals(
            ContextMaintenance.Action.RESCUE,
            decide(
                turns = 0,
                tokens = tokens,
                lastFullAtMs = now - ContextMaintenance.FULL_COOLDOWN_MS,
            ),
        )
    }

    @Test
    fun `cooldown blocks a second full pass`() {
        val tokens = (window * 0.55).toInt()
        assertEquals(
            ContextMaintenance.Action.LIGHT,
            decide(turns = 99, tokens = tokens, lastFullAtMs = now - 1_000),
        )
        assertEquals(
            ContextMaintenance.Action.FULL,
            decide(
                turns = 99, tokens = tokens,
                lastFullAtMs = now - ContextMaintenance.FULL_COOLDOWN_MS,
            ),
        )
    }

    @Test
    fun `never stacks on an in-flight compaction`() {
        assertEquals(
            ContextMaintenance.Action.NONE,
            decide(turns = 99, tokens = (window * 0.9).toInt(), isCompacting = true),
        )
    }

    @Test
    fun `a session already on a rescue digest is not rescued again`() {
        // Rescuing a rescue digest folds nothing but the digest itself. Without
        // this guard a digest that still reads as over-the-ceiling would write
        // a fresh marker on every single send.
        val tokens = (window * 0.95).toInt()
        assertEquals(
            ContextMaintenance.Action.RESCUE,
            ContextMaintenance.decide(
                userTurnsSinceFull = 1, contextTokens = tokens, contextWindow = window,
                compactSupported = true, isCompacting = false, lastFullAtMs = 0L, nowMs = now,
                alreadyRescued = false,
            ),
        )
        val guarded = ContextMaintenance.decide(
            userTurnsSinceFull = 1, contextTokens = tokens, contextWindow = window,
            compactSupported = true, isCompacting = false, lastFullAtMs = 0L, nowMs = now,
            alreadyRescued = true,
        )
        assertTrue(
            "expected LIGHT or FULL, got $guarded",
            guarded != ContextMaintenance.Action.RESCUE,
        )
    }

    @Test
    fun `small-window tiers never get a full pass`() {
        assertEquals(
            ContextMaintenance.Action.LIGHT,
            decide(turns = 99, tokens = 20_000, window = 32_000, compactSupported = false),
        )
    }

    @Test
    fun `cadence of zero is treated as every turn`() {
        val tokens = (window * 0.55).toInt()
        assertEquals(
            ContextMaintenance.Action.FULL,
            decide(turns = 1, tokens = tokens, cadence = 0),
        )
    }
}

/**
 * [T-context-maintenance] Quality post-processing. The two failure modes worth
 * defending against are filler (wastes the context the summary was meant to
 * save) and paraphrased identifiers (destroys the only content that cannot be
 * re-derived).
 */
class CompactQualityTest {

    private val path = "/var/minis/shared/openminis/apk/OpenMinis-clone.apk"
    private val sha = "b212fbc56c51520c8082c9d1254fee170250f847bef4a6cc80a0177fe89610d0"
    private val url = "https://github.com/acme/repo/actions/runs/31686665368"

    @Test
    fun `strips opening pleasantries and trailing offers`() {
        val summary = """
            Sure, here is the summary you requested.
            User asked to build the APK.
            The build succeeded.
            Let me know if you need anything else!
        """.trimIndent()
        val out = CompactQuality.stripFiller(summary)
        assertFalse(out.contains("Sure"))
        assertFalse(out.contains("Let me know"))
        assertTrue(out.contains("User asked to build the APK."))
        assertTrue(out.contains("The build succeeded."))
    }

    @Test
    fun `keeps technical lines that merely start with a filler-like word`() {
        // "OK" as a tool outcome must survive — only leading pleasantries go.
        val summary = "ran shell_execute → OK, exit 0\nbuild finished"
        val out = CompactQuality.stripFiller(summary)
        assertTrue(out.contains("exit 0"))
        assertTrue(out.contains("build finished"))
    }

    @Test
    fun `collapses the blank holes left by stripping`() {
        val summary = "Sure!\n\n\n\nReal content here."
        val out = CompactQuality.stripFiller(summary)
        assertFalse(out.contains("\n\n\n"))
        assertTrue(out.startsWith("Real content"))
    }

    @Test
    fun `criticalFacts finds paths urls and long hashes only`() {
        val facts = CompactQuality.criticalFacts("see /a/b then $path and $url plus $sha and deadbeef")
        assertTrue(facts.contains(path))
        assertTrue(facts.contains(url))
        assertTrue(facts.contains(sha))
        assertFalse(facts.contains("/a/b"))
        assertFalse(facts.contains("deadbeef"))
    }

    @Test
    fun `polish re-attaches identifiers the model paraphrased away`() {
        val transcript = "built $path, verified $sha, see $url"
        val summary = "The APK in the shared folder was built and its checksum verified."
        val out = CompactQuality.polish(transcript, summary)
        assertTrue(out.contains(path))
        assertTrue(out.contains(sha))
        assertTrue(out.contains(url))
        assertTrue(out.contains("do not paraphrase"))
    }

    @Test
    fun `polish adds no appendix when nothing was dropped`() {
        val transcript = "built $path"
        val summary = "Built $path successfully."
        val out = CompactQuality.polish(transcript, summary)
        assertFalse(out.contains("Exact references"))
        assertEquals("Built $path successfully.", out)
    }

    @Test
    fun `polish respects the char ceiling`() {
        val transcript = (1..200).joinToString(" ") { "/var/minis/workspace/file_number_$it.txt" }
        val summary = "Did some work on many files."
        val out = CompactQuality.polish(transcript, summary, maxChars = 500)
        assertTrue("was ${out.length}", out.length <= 500)
    }

    @Test
    fun `polish cleans filler and preserves facts together`() {
        val transcript = "wrote $path"
        val summary = "Certainly, here is the summary: work happened on the file."
        val out = CompactQuality.polish(transcript, summary)
        assertFalse(out.contains("Certainly"))
        assertTrue(out.contains(path))
    }
}
