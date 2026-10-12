package com.openminis.app.data

import java.util.concurrent.ConcurrentHashMap

/**
 * [T-compact-window-cache] In-process cache of compact window summaries.
 *
 * User requirement (12.10): a compact retry after a rate-limited failure
 * must NOT restart from zero — it resumes: completed windows are served
 * from cache (zero relay quota, zero wall time), only the failed and
 * remaining windows hit the model.
 *
 * Bounded: at most [MAX_ENTRIES] summaries (a compact has ≤ tens of
 * windows; each summary is a few KB), LRU-evicted beyond that. Process
 * death clears it — acceptable, since a restart is a fresh turn anyway.
 */
object CompactWindowCache {
    private const val MAX_ENTRIES = 128

    private data class Entry(val summary: String, val seq: Long)

    private val map = ConcurrentHashMap<String, Entry>()
    private val clock = java.util.concurrent.atomic.AtomicLong(0)

    fun key(windowInput: String, levelFraction: Double): String {
        val h = Integer.toHexString(windowInput.hashCode())
        return "$h:$levelFraction"
    }

    fun get(key: String): String? = map[key]?.let { e ->
        map[key] = Entry(e.summary, clock.incrementAndGet()) // LRU touch
        e.summary
    }

    fun put(key: String, summary: String) {
        if (map.size >= MAX_ENTRIES && !map.containsKey(key)) {
            // evict the least-recently-used entry
            map.entries.minByOrNull { it.value.seq }?.let { oldest ->
                map.remove(oldest.key, oldest.value)
            }
        }
        map[key] = Entry(summary, clock.incrementAndGet())
    }

    fun clear() = map.clear()
}
