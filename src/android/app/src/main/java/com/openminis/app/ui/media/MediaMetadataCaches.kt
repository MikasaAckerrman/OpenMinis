package com.openminis.app.ui.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [T-media-lazy-player] Off-main, cached media metadata reads.
 *
 * Device forensics (01.10): the frequent SIGABRT crashes were preceded by
 * "mmap failed: Out of memory" in worker threads — the process had created
 * ~10k threads within 15 minutes (TIDs 9908/10514). The audio/video tiles
 * constructed a full MediaPlayer + prepare() DURING composition just to
 * read `duration` — every tile entering the viewport spun the native codec
 * pipeline (threads + buffers). MediaMetadataRetriever is a demuxer probe,
 * not a playback pipeline: short-lived, released immediately, run off the
 * main thread. A file's duration never changes — cache per absolute path.
 */
object MediaMetadataCaches {
    private val durationCache: MutableMap<String, Long> =
        java.util.Collections.synchronizedMap(HashMap<String, Long>())

    /** Duration in ms; 0 when unreadable. Negative results are cached too. */
    suspend fun durationMs(file: File): Long = withContext(Dispatchers.IO) {
        val key = file.absolutePath
        durationCache[key]?.let { return@withContext it }
        val d = try {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(key)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } finally {
                runCatching { r.release() }
            }
        } catch (_: Throwable) {
            0L
        }
        durationCache[key] = d
        d
    }
}
