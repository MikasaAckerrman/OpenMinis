package com.openminis.app.memory

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-m13-fts-memory] Backend switch for associative memory search:
 * [FtsMemoryIndex] (in-process SQLite FTS5) vs the supermemory Node
 * server. Default: FTS — the Node server's costs are measured
 * (10–26s boot, ~245MB RSS, 3.5s timeouts under thermal pressure) while
 * the FTS path is zero-boot and millisecond-scale. debug.memoryFts
 * {"enabled":false} reverts to the server for a live A/B.
 */
object MemorySearchPrefs {
    private const val PREFS = "minis_debug_memory_search"
    private const val KEY = "fts"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun useFts(): Boolean {
        val p = prefs ?: return true
        return p.getBoolean(KEY, true)
    }

    fun setUseFts(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY, value)?.apply()
    }

    /** [crash-2026-10-05_20-30] Guard for the capability fallback. */
    fun isInitialized(): Boolean = prefs != null
}
