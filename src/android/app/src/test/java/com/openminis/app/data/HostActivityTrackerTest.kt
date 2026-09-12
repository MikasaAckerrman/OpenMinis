package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-stale-conn-per-host] Regression tests for the per-host idle
 * bookkeeping behind the pre-flight stale-socket eviction.
 *
 * The bug these lock down: with ONE app-wide activity stamp, a busy session on
 * provider A masked an idle session on provider B — B's pre-flight check saw
 * fresh activity, skipped eviction, wrote into its own NAT-reaped socket and
 * hung until the TTFB watchdog fired (user-visible as
 * "no response from server (120s)" on a session that was merely idle, at 10%
 * context, with no compaction involved).
 */
class HostActivityTrackerTest {

    private var clock = 0L
    private fun tracker(maxHosts: Int = HostActivityTracker.DEFAULT_MAX_HOSTS) =
        HostActivityTracker(maxHosts = maxHosts, nowMs = { clock })

    // ── Core per-host isolation (the actual bug) ────────────────────────────

    @Test
    fun `busy host does not mask idle host`() {
        val t = tracker()
        t.mark("api.openai.com")
        t.mark("gorouter.app")

        // openai keeps streaming for a minute; gorouter stays silent.
        repeat(6) {
            clock += 10_000
            t.mark("api.openai.com")
        }

        // gorouter has been idle 60s → MUST evict before its next request.
        assertTrue(
            "idle host must be judged by its own last byte, not another host's",
            t.shouldEvictAndClear("gorouter.app"),
        )
    }

    @Test
    fun `busy host itself is not evicted`() {
        val t = tracker()
        t.mark("api.openai.com")
        clock += 5_000
        t.mark("api.openai.com")
        clock += 1_000

        assertFalse(
            "warm socket on the hot path must be reused",
            t.shouldEvictAndClear("api.openai.com"),
        )
    }

    // ── Threshold boundaries ───────────────────────────────────────────────

    @Test
    fun `evicts exactly at threshold`() {
        val t = tracker()
        t.mark("h")
        clock += StaleConnectionPolicy.STALE_IDLE_THRESHOLD_MS
        assertTrue(t.shouldEvictAndClear("h"))
    }

    @Test
    fun `does not evict one ms below threshold`() {
        val t = tracker()
        t.mark("h")
        clock += StaleConnectionPolicy.STALE_IDLE_THRESHOLD_MS - 1
        assertFalse(t.shouldEvictAndClear("h"))
    }

    @Test
    fun `unknown host never evicts`() {
        val t = tracker()
        t.mark("known")
        clock += 10 * StaleConnectionPolicy.STALE_IDLE_THRESHOLD_MS
        assertFalse(
            "no observed activity means nothing was pooled — pool keepAlive is the backstop",
            t.shouldEvictAndClear("never-seen"),
        )
    }

    // ── Stamp clearing ─────────────────────────────────────────────────────

    @Test
    fun `stamp cleared after eviction so second check is quiet`() {
        val t = tracker()
        t.mark("h")
        clock += StaleConnectionPolicy.STALE_IDLE_THRESHOLD_MS
        assertTrue(t.shouldEvictAndClear("h"))
        assertFalse(
            "stamp described a socket that no longer exists",
            t.shouldEvictAndClear("h"),
        )
    }

    @Test
    fun `re-marking after eviction restarts the window`() {
        val t = tracker()
        t.mark("h")
        clock += StaleConnectionPolicy.STALE_IDLE_THRESHOLD_MS
        assertTrue(t.shouldEvictAndClear("h"))

        t.mark("h") // fresh socket delivered a byte
        clock += 1_000
        assertFalse(t.shouldEvictAndClear("h"))
    }

    // ── Bounded growth ─────────────────────────────────────────────────────

    @Test
    fun `host map stays bounded and drops least recently active`() {
        val t = tracker(maxHosts = 3)
        t.mark("a"); clock += 1_000
        t.mark("b"); clock += 1_000
        t.mark("c"); clock += 1_000
        assertEquals(3, t.trackedHostCount())

        t.mark("d") // evicts "a", the least recently active
        assertEquals(3, t.trackedHostCount())

        clock += StaleConnectionPolicy.STALE_IDLE_THRESHOLD_MS
        assertFalse("evicted entry behaves as unknown", t.shouldEvictAndClear("a"))
        assertTrue("surviving entries still tracked", t.shouldEvictAndClear("b"))
    }

    @Test
    fun `re-marking existing host at capacity does not evict others`() {
        val t = tracker(maxHosts = 2)
        t.mark("a"); clock += 1_000
        t.mark("b"); clock += 1_000
        t.mark("a") // existing key — no eviction should occur
        assertEquals(2, t.trackedHostCount())

        clock += StaleConnectionPolicy.STALE_IDLE_THRESHOLD_MS
        assertTrue(t.shouldEvictAndClear("b"))
    }

    // ── Layering with the pool keepAlive ───────────────────────────────────

    @Test
    fun `threshold sits below the pool keepAlive so defences layer`() {
        // Pool keepAlive is 30s; the pre-flight threshold must be strictly
        // lower, otherwise the pool would already have closed the socket and
        // this check could never contribute.
        assertTrue(
            "pre-flight threshold must be below the 30s pool keepAlive",
            StaleConnectionPolicy.STALE_IDLE_THRESHOLD_MS < 30_000L,
        )
    }
}
