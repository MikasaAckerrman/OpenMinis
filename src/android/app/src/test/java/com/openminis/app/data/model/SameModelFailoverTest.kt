package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-same-model-failover] The user's journal showed two 120s silences on
 * gorouter.app followed by GIVEUP, while the same model was available on 55
 * other instances. These tests pin the ordering that makes the second door get
 * tried.
 */
class SameModelFailoverTest {

    private fun c(
        id: String,
        model: String = "claude-opus-5-thinking",
        host: String = "gorouter.app",
        enabled: Boolean = true,
        hidden: Boolean = false,
        key: Boolean = true,
    ) = SameModelFailover.Candidate(
        entryId = id,
        instanceId = "inst-$id",
        modelId = model,
        host = host,
        isEnabled = enabled,
        isHidden = hidden,
        hasCredential = key,
    )

    // ── the reported failure ────────────────────────────────────────────────

    @Test
    fun `a different host is preferred over another key on the dead host`() {
        // The failure being escaped belongs to the HOST (a silent node), so
        // another key on that same host is the least likely to help.
        val all = listOf(
            c("dead"),
            c("same-host-2"),
            c("other", host = "agentrouter.org"),
        )
        val got = SameModelFailover.candidatesFor(
            modelId = "claude-opus-5-thinking",
            failedEntryId = "dead",
            failedHost = "gorouter.app",
            all = all,
        )
        assertEquals(listOf("other", "same-host-2"), got.map { it.entryId })
    }

    @Test
    fun `the endpoint that just failed is never retried`() {
        val all = listOf(c("dead"), c("live", host = "agentrouter.org"))
        val got = SameModelFailover.candidatesFor(
            "claude-opus-5-thinking", "dead", "gorouter.app", all,
        )
        assertTrue(got.none { it.entryId == "dead" })
    }

    @Test
    fun `the same host is still tried when it is the only option left`() {
        // A relay can fail per account/route while the host itself is fine, so
        // same-host keys are a later resort — not excluded.
        val all = listOf(c("dead"), c("sibling"))
        val got = SameModelFailover.candidatesFor(
            "claude-opus-5-thinking", "dead", "gorouter.app", all,
        )
        assertEquals(listOf("sibling"), got.map { it.entryId })
    }

    // ── what must never be swapped in ───────────────────────────────────────

    @Test
    fun `a different model is never substituted`() {
        // A "similar" model is a different answer. Silently swapping it would be
        // worse than surfacing the error.
        val all = listOf(c("other-model", model = "claude-opus-5"))
        val got = SameModelFailover.candidatesFor(
            "claude-opus-5-thinking", "dead", "gorouter.app", all,
        )
        assertTrue(got.isEmpty())
    }

    @Test
    fun `disabled instances and missing credentials are skipped`() {
        val all = listOf(
            c("off", host = "a.example", enabled = false),
            c("nokey", host = "b.example", key = false),
            c("ok", host = "c.example"),
        )
        val got = SameModelFailover.candidatesFor(
            "claude-opus-5-thinking", "dead", "gorouter.app", all,
        )
        assertEquals(listOf("ok"), got.map { it.entryId })
    }

    @Test
    fun `a hidden entry is still a usable transport`() {
        // Hiding is a picker preference, not "do not use". Excluding hidden
        // entries would throw away working doors for a cosmetic reason.
        val all = listOf(c("hidden-one", host = "a.example", hidden = true))
        val got = SameModelFailover.candidatesFor(
            "claude-opus-5-thinking", "dead", "gorouter.app", all,
        )
        assertEquals(listOf("hidden-one"), got.map { it.entryId })
    }

    // ── bounding ────────────────────────────────────────────────────────────

    @Test
    fun `the candidate list is capped so a doomed turn fails fast`() {
        // The user has 56 endpoints for this model. Marching through all of them
        // would keep a hopeless turn alive for minutes.
        val all = (1..56).map { c("e$it", host = "h$it.example") }
        val got = SameModelFailover.candidatesFor(
            "claude-opus-5-thinking", null, "gorouter.app", all,
        )
        assertEquals(SameModelFailover.DEFAULT_MAX_CANDIDATES, got.size)
        assertEquals(3, SameModelFailover.DEFAULT_MAX_CANDIDATES)
    }

    @Test
    fun `a non-positive cap means unlimited, not disabled`() {
        // `take(0)` would silently switch failover OFF — the exact failure this
        // object removes.
        val all = (1..7).map { c("e$it", host = "h$it.example") }
        assertEquals(
            7,
            SameModelFailover.candidatesFor(
                "claude-opus-5-thinking", null, null, all, maxCandidates = 0,
            ).size,
        )
        assertEquals(
            7,
            SameModelFailover.candidatesFor(
                "claude-opus-5-thinking", null, null, all, maxCandidates = -1,
            ).size,
        )
    }

    // ── determinism ─────────────────────────────────────────────────────────

    @Test
    fun `order within a bucket is stable so the result is predictable`() {
        val all = listOf(
            c("z", host = "z.example"),
            c("a", host = "a.example"),
            c("m", host = "m.example"),
        )
        val got = SameModelFailover.candidatesFor(
            "claude-opus-5-thinking", null, "gorouter.app", all, maxCandidates = 0,
        )
        assertEquals(listOf("z", "a", "m"), got.map { it.entryId })
    }

    @Test
    fun `host comparison ignores case`() {
        val all = listOf(c("same", host = "GoRouter.app"), c("other", host = "x.example"))
        val got = SameModelFailover.candidatesFor(
            "claude-opus-5-thinking", null, "gorouter.app", all, maxCandidates = 0,
        )
        assertEquals(listOf("other", "same"), got.map { it.entryId })
    }

    @Test
    fun `a null failed host keeps every candidate in the preferred bucket`() {
        val all = listOf(c("a", host = "a.example"), c("b", host = "b.example"))
        val got = SameModelFailover.candidatesFor(
            "claude-opus-5-thinking", null, null, all, maxCandidates = 0,
        )
        assertEquals(listOf("a", "b"), got.map { it.entryId })
    }
}
