package com.openminis.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil.request.ImageRequest
import coil.size.Precision

/**
 * [T-android-canvas-large-bitmap-crash] Decode-size ceiling for images we hand
 * to Compose / Canvas for DISPLAY.
 *
 * Background: a markdown attachment (`telecom_whole_market.png`, a very tall
 * chart) crashed on a vivo V2454DA / Android 16 with
 *
 *     java.lang.RuntimeException: Canvas: trying to draw too large(215040000bytes) bitmap
 *       at android.graphics.RecordingCanvas.throwIfCannotDraw
 *
 * 215,040,000 bytes / 4 (ARGB_8888) = 53.76 Mpx — e.g. a ~3000x17920 chart.
 * `RecordingCanvas` refuses any bitmap above roughly 100MB (the exact ceiling
 * tracks the GPU's max texture dimension), so the draw throws and takes the
 * process down from `ThreadedRenderer.draw`.
 *
 * Why it got that big: the markdown image renderer used
 * `SubcomposeAsyncImage(model = file, contentScale = ContentScale.FillWidth)`
 * with NO `ImageRequest` size. Coil sizes a request from the layout
 * constraints, but the markdown column is vertically scrollable, so the height
 * constraint is `Constraints.Infinity`. With an unbounded dimension Coil falls
 * back to the image's INTRINSIC size and decodes the PNG at full resolution.
 * A wide-but-short image survives that (width is bounded); a very tall one does
 * not.
 *
 * The fix caps the decode instead of scaling after the fact: capping at the
 * `ImageRequest` level means the giant bitmap is never allocated, which also
 * removes the OOM risk that a decode-then-downscale approach would keep.
 *
 * This is a DISPLAY-side guard only. It is unrelated to
 * [com.openminis.app.provider.ImageBudget], which caps bytes sent to LLM
 * providers on the network path.
 */
object DisplayBitmapLimits {

    /**
     * Longest-edge ceiling, in pixels, for a bitmap decoded for on-screen
     * display.
     *
     * 4096 is the max texture dimension essentially every GPU in our install
     * base supports, so a bitmap within this bound is always drawable. The
     * worst case it admits is 4096x4096 ARGB_8888 = 64MB, comfortably under
     * the ~100MB `RecordingCanvas` ceiling. It is also far above any phone or
     * tablet viewport, so a fullscreen/zoomed viewer still has ample detail to
     * pan around in.
     */
    const val MAX_DISPLAY_EDGE_PX = 4096

    /**
     * Return a power-of-two decode sample that puts both bitmap dimensions at
     * or below [maxEdge]. Pure arithmetic keeps thumbnail safety testable.
     */
    fun calculateInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        maxEdge: Int = MAX_DISPLAY_EDGE_PX,
    ): Int {
        require(maxEdge > 0) { "maxEdge must be > 0" }
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        var sample = 1
        while (maxOf(sourceWidth / sample, sourceHeight / sample) > maxEdge) {
            sample *= 2
        }
        return sample
    }

    /** Decode a local image for a thumbnail without allocating its original. */
    fun decodeFileForDisplay(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, options)
    }

    /**
     * Apply the display decode ceiling to an [ImageRequest.Builder].
     *
     * Uses `Precision.INEXACT` so Coil is free to honour the ceiling by
     * downsampling to the nearest power-of-two sample size rather than
     * producing an exactly-sized bitmap — cheaper, and we only care about the
     * upper bound, not an exact pixel count.
     *
     * Coil scales DOWN to fit this bound and never scales up, so normal-sized
     * images (the overwhelming majority) decode exactly as they did before and
     * their rendering is unchanged. Only images that would otherwise exceed the
     * ceiling are affected.
     */
    fun ImageRequest.Builder.limitDisplaySize(): ImageRequest.Builder =
        size(MAX_DISPLAY_EDGE_PX, MAX_DISPLAY_EDGE_PX)
            .precision(Precision.INEXACT)

    /**
     * [T-runtime-bitmap-canvas-crash] Cap an ALREADY-decoded bitmap to
     * [maxEdge] before it is handed to Compose/Canvas for display.
     *
     * The [decodeFileForDisplay] / [limitDisplaySize] paths above only guard
     * bitmaps we decode from a file through Coil or BitmapFactory. Bitmaps
     * produced at RUNTIME bypass them entirely and can be arbitrarily large:
     *
     *  - `MediaMetadataRetriever.getFrameAtTime(...)` returns a video frame at
     *    the clip's native resolution (a 4K clip → 3840x2160 = 33Mpx),
     *  - a KaTeX/HTML WebView snapshot or a `GraphicsLayer` capture is sized to
     *    the (unbounded, scrollable) content height,
     *  - `Bitmap.createBitmap` / `drawable.toBitmap` at a computed size.
     *
     * Drawing any of these inside a `RecordingCanvas` throws
     * `Canvas: trying to draw too large (… bytes) bitmap` above ~100MB and
     * takes the whole process down — which is exactly the "open this session
     * and the app dies" crash. A 61.6Mpx runtime bitmap (246MB ARGB_8888) was
     * observed doing this.
     *
     * This scales the bitmap DOWN with a filtered `createScaledBitmap` only
     * when it exceeds the ceiling; normal-sized bitmaps are returned unchanged
     * (no copy, no allocation). Never scales up. Returns null only if the input
     * is null. Any failure to scale falls back to returning the original rather
     * than throwing, since the caller already has nothing better.
     */
    fun capForDisplay(source: Bitmap?, maxEdge: Int = MAX_DISPLAY_EDGE_PX): Bitmap? {
        if (source == null) return null
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source
        val longest = maxOf(w, h)
        if (longest <= maxEdge) return source
        val scale = maxEdge.toDouble() / longest.toDouble()
        val targetW = maxOf(1, Math.round(w * scale).toInt())
        val targetH = maxOf(1, Math.round(h * scale).toInt())
        return try {
            Bitmap.createScaledBitmap(source, targetW, targetH, /* filter = */ true)
        } catch (_: Throwable) {
            // Out of memory or any other failure: the ORIGINAL is still too big
            // to draw safely, so signal "no bitmap" rather than hand back a
            // canvas bomb.
            null
        }
    }
}
