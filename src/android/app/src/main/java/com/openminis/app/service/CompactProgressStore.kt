package com.openminis.app.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.openminis.app.data.CompactFailure
import com.openminis.app.data.CompactPhase
import com.openminis.app.data.CompactProgress
import com.openminis.app.data.CompactRouteAttempt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-compact-progress-persistent] Survives compact progress across
 * session navigation and process death. Mirrors SessionBadgeStore.
 *
 * Problem: the live _compactProgress StateFlow lives on ChatViewModel — when
 * the user navigates away (or the process is killed) the VM is destroyed and
 * the progress card disappears. The user can't tell whether compact is still
 * running in the background or has stopped. Once we restore, the card reappears
 * with elapsed timer that survived the gap.
 *
 * Storage: SharedPreferences per sessionId. Snapshot includes the monotonic
 * startMs of the original compact — the timer shows real wall-clock time,
 * not "elapsed since restore". Only ONE active compact per session at a time:
 * a new compact replaces the previous snapshot.
 *
 * We DON'T restore a DONE compact (it already finished — nothing actionable
 * to show). Only RUNNING compacts (PREPARING..WRITING) are persisted+restored.
 */
object CompactProgressStore {
    private const val TAG = "CompactProgressStore"
    private const val PREFS = "compact_progress_store"

    @Volatile private var prefs: SharedPreferences? = null

    // sessionId -> most recent snapshot of an in-flight compact.
    private val _byId = MutableStateFlow<Map<String, CompactProgress>>(emptyMap())
    val byId: StateFlow<Map<String, CompactProgress>> = _byId.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _byId.value = loadFromDisk(p)
        Log.d(TAG, "init: restored ${_byId.value.size} active compacts")
    }

    /** Persist the current snapshot for [sessionId] (replaces any previous). */
    fun put(sessionId: String, snapshot: CompactProgress) {
        if (snapshot.phase == CompactPhase.DONE || snapshot.failure != null) {
            clear(sessionId)
            return
        }
        mutate { current ->
            current + (sessionId to snapshot)
        }
        writeToDisk(sessionId, snapshot)
    }

    /** Drop the snapshot for [sessionId] (compact finished, app re-entered, etc). */
    fun clear(sessionId: String) {
        mutate { it - sessionId }
        prefs?.edit()?.remove("compact_$sessionId")?.apply()
    }

    /** Head (most-recent) snapshot for [sessionId], or null when none. */
    fun snapshotFor(sessionId: String): CompactProgress? = _byId.value[sessionId]

    private fun mutate(transform: (Map<String, CompactProgress>) -> Map<String, CompactProgress>) {
        synchronized(this) {
            _byId.value = transform(_byId.value)
        }
    }

    private fun writeToDisk(sessionId: String, p: CompactProgress) {
        val sp = prefs ?: return
        val json = JSONObject().apply {
            put("startMs", p.startMs)
            put("phase", p.phase.name)
            // Float percent with 4 decimals — 1.03837% granularity.
            // UI shows this directly without further formatting.
            put("percent", p.percent.toFloat() + p.chunkIndex.toFloat() / 100f / p.chunkCount.coerceAtLeast(1))
            put("chunkIndex", p.chunkIndex)
            put("chunkCount", p.chunkCount)
            put("modelLabel", p.modelLabel)
            put("routeNote", p.routeNote)
            if (p.failure != null) {
                val attempts = JSONArray()
                for (a in p.failure.attempts) {
                    attempts.put(JSONObject().apply {
                        put("modelId", a.modelId)
                        put("message", a.message)
                    })
                }
                put("failure", JSONObject().apply {
                    put("attempts", attempts)
                    put("terminal", p.failure.terminal)
                })
            }
        }
        sp.edit().putString("compact_$sessionId", json.toString()).apply()
    }

    private fun loadFromDisk(p: SharedPreferences): Map<String, CompactProgress> {
        val out = mutableMapOf<String, CompactProgress>()
        for ((k, v) in p.all) {
            if (!k.startsWith("compact_") || v !is String) continue
            val sessionId = k.removePrefix("compact_")
            try {
                val json = JSONObject(v)
                val phase = runCatching { CompactPhase.valueOf(json.optString("phase", "PREPARING")) }
                    .getOrDefault(CompactPhase.PREPARING)
                val failure = json.optJSONObject("failure")?.let { f ->
                    val attempts = mutableListOf<CompactRouteAttempt>()
                    val arr = f.optJSONArray("attempts")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val a = arr.optJSONObject(i) ?: continue
                        attempts += CompactRouteAttempt(
                            modelId = a.optString("modelId"),
                            message = a.optString("message"),
                        )
                    }
                    CompactFailure(attempts = attempts, terminal = f.optString("terminal"))
                }
                out[sessionId] = CompactProgress(
                    startMs = json.optLong("startMs", System.currentTimeMillis()),
                    phase = phase,
                    // On restore, percent is rebuilt from chunkIndex/chunkCount so
                    // the elapsed-time display matches where we left off.
                    percent = json.optInt("chunkIndex", 1)
                        .coerceAtMost(json.optInt("chunkCount", 1))
                        .let { (it.toFloat() / json.optInt("chunkCount", 1).coerceAtLeast(1) * 100f).toDouble() },
                    chunkIndex = json.optInt("chunkIndex", 1),
                    chunkCount = json.optInt("chunkCount", 1),
                    modelLabel = json.optString("modelLabel").ifBlank { null },
                    routeNote = json.optString("routeNote").ifBlank { null },
                    failure = failure,
                )
            } catch (e: Throwable) {
                Log.w(TAG, "loadFromDisk: skip $sessionId — ${e.message}")
            }
        }
        return out
    }
}
