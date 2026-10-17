package com.openminis.app.data

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-compact-persist-cache] Disk-backed store for compact window summaries.
 *
 * The in-memory [CompactWindowCache] process-dies with the app: a rate-limited
 * compact interrupted at window 9/13 loses ALL finished summaries and a retry
 * redoes the entire map phase — the exact failure the user keeps hitting on
 * free relays. This store persists the same key -> summary map per session on
 * disk (bounded, atomic rename, corruption-tolerant), so:
 *  - a retry after 429/timeout resumes finished windows instantly;
 *  - a process restart mid-compact loses nothing;
 *  - retry minutes later (rate window resets) continues, does not restart.
 *
 * Semantics are identical to the in-memory cache: same key (level + content
 * hash), last-write-wins, LRU-ish trim of the oldest entries by insertion
 * order. Every IO is best-effort — a disk failure must NEVER break or enter
 * the failure path of a compact; callers ship with an in-memory fallback.
 */
object CompactDiskCache {

    private const val TAG = "CompactDiskCache"
    const val MAX_ENTRIES = 128
    private const val DIR = "compact-cache"

    private val unsafeChars = Regex("[^A-Za-z0-9._-]")

    fun cacheFile(context: Context, sessionId: String): File {
        return file(context.filesDir, sessionId)
    }

    /** Android-free overload for unit tests: same naming rules, explicit root. */
    fun file(rootDir: File, sessionId: String): File {
        val safe = sessionId.replace(unsafeChars, "_").ifEmpty { "global" }
        return File(File(rootDir, DIR), "$safe.json")
    }

    /**
     * [T-compact-cache-hygiene] Drop this session's window cache after a
     * COMMITTED compaction: every window it held is now folded into the
     * summary, so the file is dead weight (it used to grow forever). Never
     * called on cancellation/failure — there the cache is exactly what makes
     * the retry cheap.
     */
    fun clear(context: Context, sessionId: String) {
        runCatching { file(context.filesDir, sessionId).delete() }
            .onFailure {
                Log.w(TAG, "clear failed (${it.javaClass.simpleName}) — entries age out by LRU")
            }
    }

    /** Load the stored window summaries for this session. Empty on any failure. */
    fun load(file: File): LinkedHashMap<String, String> {
        return runCatching {
            if (!file.isFile) return@runCatching LinkedHashMap<String, String>()
            val arr = JSONArray(file.readText())
            val map = LinkedHashMap<String, String>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map.put(o.getString("k"), o.getString("v"))
            }
            map
        }.getOrElse { e ->
            Log.w(TAG, "load failed (${e.javaClass.simpleName}) — starting with an empty cache")
            LinkedHashMap()
        }
    }

    /** Write-through put: insert/replace key, trim oldest beyond the cap, persist atomically. */
    fun put(file: File, map: LinkedHashMap<String, String>, key: String, value: String) {
        runCatching {
            map.remove(key)
            map[key] = value
            while (map.size > MAX_ENTRIES) {
                map.remove(map.keys.first())
            }
            val arr = JSONArray()
            for ((k, v) in map) {
                arr.put(JSONObject().apply {
                    put("k", k)
                    put("v", v)
                })
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString(0))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { e ->
            Log.w(TAG, "persist failed (${e.javaClass.simpleName}) — memory copy still serves this run")
        }
    }
}
