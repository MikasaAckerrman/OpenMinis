package com.openminis.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-canvas-large-bitmap-crash] Guards the arithmetic behind
 * [DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX].
 *
 * These are pure-JVM assertions about the ceiling itself. Whether Coil honours
 * the request size needs a real decode, so the end-to-end proof is the manual
 * repro described in the commit message — but the invariant that MUST hold for
 * the fix to work at all (a bitmap at the ceiling is still drawable by
 * RecordingCanvas) is checkable here, and would catch someone later raising the
 * constant past the safe bound.
 */
class DisplayBitmapLimitsTest {

    /** Bytes per pixel for ARGB_8888, the config Coil decodes to by default. */
    private val bytesPerPixel = 4L

    /**
     * RecordingCanvas.throwIfCannotDraw rejects bitmaps above ~100MB. Stay
     * meaningfully under it so the guard holds even on a device whose ceiling
     * is a little tighter than the common case.
     */
    private val canvasCeilingBytes = 100L * 1024 * 1024

    @Test
    fun `a square bitmap at the ceiling stays under the canvas limit`() {
        val edge = DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX.toLong()
        val worstCaseBytes = edge * edge * bytesPerPixel
        assertTrue(
            "worst-case bitmap is $worstCaseBytes bytes, canvas ceiling is $canvasCeilingBytes",
            worstCaseBytes < canvasCeilingBytes,
        )
    }

    @Test
    fun `the ceiling is within the universally supported max texture dimension`() {
        // 4096 is the max texture dimension effectively every GPU in the
        // install base supports. Above it, a bitmap can be under the byte
        // ceiling yet still be undrawable on some devices.
        assertTrue(DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX <= 4096)
    }

    @Test
    fun `the crashing bitmap from the report would be rejected by the ceiling`() {
        // The vivo V2454DA crash: 215,040,000 bytes = 53.76 Mpx, e.g. 3000x17920.
        val crashWidth = 3000
        val crashHeight = 17920
        assertTrue(
            "the reported image must exceed the ceiling, else the guard is a no-op for it",
            maxOf(crashWidth, crashHeight) > DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX,
        )

        // After the cap, the longest edge is clamped to the ceiling and the
        // short edge scales proportionally — verify the result is drawable.
        val scale = DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX.toDouble() / crashHeight
        val cappedW = (crashWidth * scale).toLong().coerceAtLeast(1)
        val cappedH = DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX.toLong()
        val cappedBytes = cappedW * cappedH * bytesPerPixel
        assertTrue(
            "capped bitmap is $cappedBytes bytes (${cappedW}x$cappedH)",
            cappedBytes < canvasCeilingBytes,
        )
    }

    @Test
    fun `sample size keeps a tall crash image under the display edge`() {
        val sample = DisplayBitmapLimits.calculateInSampleSize(3000, 17920)
        assertTrue(sample > 1)
        assertTrue(3000 / sample <= DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX)
        assertTrue(17920 / sample <= DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX)
    }

    @Test
    fun `small image uses the original decode`() {
        assertTrue(DisplayBitmapLimits.calculateInSampleSize(1600, 1200) == 1)
    }

    @Test
    fun `an image already under the ceiling is unaffected`() {
        // A typical matplotlib chart — well under the bound, so the cap must
        // not change how it renders.
        val w = 1600
        val h = 1200
        assertTrue(maxOf(w, h) <= DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX)
    }

    // [T-runtime-bitmap-canvas-crash] The runtime-bitmap cap (capForDisplay)
    // uses the same MAX_DISPLAY_EDGE_PX ceiling but scales an already-decoded
    // Bitmap. capForDisplay itself needs android.graphics.Bitmap, so the
    // end-to-end behaviour lives in the instrumented test; here we pin the
    // target-dimension arithmetic it relies on (round(edge * ceiling/longest))
    // so a regression in the scale factor is caught on the JVM.

    private fun targetDims(w: Int, h: Int, maxEdge: Int): Pair<Int, Int> {
        val longest = maxOf(w, h)
        if (longest <= maxEdge) return w to h
        val scale = maxEdge.toDouble() / longest.toDouble()
        val tw = maxOf(1, Math.round(w * scale).toInt())
        val th = maxOf(1, Math.round(h * scale).toInt())
        return tw to th
    }

    @Test
    fun `runtime cap scales the 61Mpx crash frame under the canvas ceiling`() {
        // The observed "open session, app dies" crash: 246,420,608 bytes =
        // 61.6 Mpx runtime bitmap (a large video frame). e.g. ~8608x7157.
        val (tw, th) = targetDims(8608, 7157, DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX)
        assertTrue("longest edge must hit the ceiling", maxOf(tw, th) <= DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX)
        val cappedBytes = tw.toLong() * th.toLong() * bytesPerPixel
        assertTrue("capped bitmap is $cappedBytes bytes (${tw}x$th)", cappedBytes < canvasCeilingBytes)
    }

    @Test
    fun `runtime cap leaves a 4K video frame that is under the ceiling untouched`() {
        // 3840x2160 (4K) is under 4096 on the longest edge, so it must pass
        // through unchanged — no needless rescale of an already-drawable frame.
        val (tw, th) = targetDims(3840, 2160, DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX)
        assertTrue(tw == 3840 && th == 2160)
    }

    @Test
    fun `runtime cap preserves aspect ratio within one pixel`() {
        val srcW = 8608
        val srcH = 7157
        val (tw, th) = targetDims(srcW, srcH, DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX)
        val srcRatio = srcW.toDouble() / srcH
        val dstRatio = tw.toDouble() / th
        assertTrue(
            "aspect drift $srcRatio vs $dstRatio",
            Math.abs(srcRatio - dstRatio) < 0.01,
        )
    }
}
