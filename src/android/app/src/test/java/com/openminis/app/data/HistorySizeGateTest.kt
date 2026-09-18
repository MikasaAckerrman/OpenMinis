package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-effective-size-honesty] The content-filter "is the payload big enough
 * that size is a credible cause" decision must be made on the EFFECTIVE
 * payload (summary + live tail — the bytes that actually go on the wire),
 * not on the raw DB history.
 *
 * The live failure this guards against: the user compacts a 339k-token
 * session down to a ~1.2k-token effective payload, a relay content filter
 * still rejects the small body, and the app — reading the RAW history
 * (339k) — told the user "payload too big, run /compact". The user already
 * ran it; the advice lied and masked the real cause (a relay-side filter).
 */
class HistorySizeGateTest {

    @Test
    fun `compacted session tail is not large even when raw history was huge`() {
        // Effective tokens after /compact: summary marker + last turns ≈ 1.2k.
        // Raw history in DB stays at 339k — must NOT influence the decision.
        assertFalse(
            HistorySizeGate.isLarge(
                effectiveTokens = 1_200,
                contextWindowTokens = 128_000,
            ),
        )
    }

    @Test
    fun `genuinely big effective payload is large`() {
        // A 680 KB body ≈ 190k effective tokens on a 128k-window model —
        // size IS a credible cause for a content filter rejection.
        assertTrue(
            HistorySizeGate.isLarge(
                effectiveTokens = 190_000,
                contextWindowTokens = 128_000,
            ),
        )
    }

    @Test
    fun `threshold is half the window with a 24k cap`() {
        // window/2 = 64k, capped at 24k → 24k tokens is the decision point.
        assertFalse(HistorySizeGate.isLarge(23_999, 128_000))
        assertTrue(HistorySizeGate.isLarge(24_000, 128_000))
    }

    @Test
    fun `unknown window falls back to conservative 24k floor`() {
        assertFalse(HistorySizeGate.isLarge(23_999, null))
        assertTrue(HistorySizeGate.isLarge(24_000, null))
    }

    @Test
    fun `tiny window still keeps the 8k minimum threshold`() {
        // A 12k-token window would naively give a 6k threshold; the floor
        // keeps ordinary small sessions from being called "large". The
        // boundary is inclusive (>=), matching the original isRawHistoryLarge.
        assertFalse(HistorySizeGate.isLarge(7_999, 12_000))
        assertTrue(HistorySizeGate.isLarge(8_000, 12_000))
    }
}
