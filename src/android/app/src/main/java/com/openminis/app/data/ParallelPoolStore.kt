package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * [B53-parallel-pool] The USER-CURATED pool of models for parallel worker
 * routing: spawn_many children pick pool[i mod size] round-robin, so each
 * worker hits its OWN provider host and the single-relay queue disappears.
 * Stabilizes EXECUTION (relay queues are per-host); issuance — the model's
 * calls-per-round shape — is untouched (measured 14/17 singles on 13.11).
 *
 * Owner-only BY DESIGN: minis-config's documented surface lets the MODEL add
 * providers — this store is a separate prefs file with no tool or config path
 * to it; entries are added/removed only from the chat plaque UI. A stale
 * entry (model deleted in Settings) does not poison a batch: the executor
 * checks visibility at spawn time and falls back to the standard resolve.
 *
 * Same-host entries add model diversity but no parallel gain — the plaque
 * displays the host so the user sees which entries actually parallel.
 */
object ParallelPoolStore {

    data class PoolEntry(
        val modelEntryId: String,
        val displayName: String,
        val host: String,
        val enabled: Boolean,
    )

    private const val PREFS = "minis_parallel_pool"
    private const val KEY_POOL = "pool_v1"

    @Volatile
    private var appContext: Context? = null

    private val _entries = MutableStateFlow<List<PoolEntry>>(emptyList())

    /** Observable pool for the chat plaque; add/remove/toggle recompose it. */
    val entries: StateFlow<List<PoolEntry>> = _entries.asStateFlow()

    private val _masterEnabled = MutableStateFlow(false)

    /** Master switch: OFF means every spawn uses the standard resolve chain. */
    val masterEnabled: StateFlow<Boolean> = _masterEnabled.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Capture the app context and publish the persisted state. MinisApp.onCreate. */
    fun prime(context: Context) {
        appContext = context.applicationContext
        val (enabled, entries) = parsePool(
            prefs(context).getString(KEY_POOL, null),
        )
        _masterEnabled.value = enabled
        _entries.value = entries
    }

    fun setEnabled(on: Boolean) {
        _masterEnabled.value = on
        persist()
    }

    fun addEntry(modelEntryId: String, displayName: String, host: String) {
        if (modelEntryId.isBlank()) return
        val current = _entries.value
        if (current.any { it.modelEntryId == modelEntryId }) return
        _entries.value = current + PoolEntry(modelEntryId, displayName, host, enabled = true)
        persist()
    }

    fun removeEntry(modelEntryId: String) {
        _entries.value = _entries.value.filterNot { it.modelEntryId == modelEntryId }
        persist()
    }

    fun setEntryEnabled(modelEntryId: String, on: Boolean) {
        _entries.value = _entries.value.map {
            if (it.modelEntryId == modelEntryId) it.copy(enabled = on) else it
        }
        persist()
    }

    private fun persist() {
        val ctx = appContext ?: return
        prefs(ctx).edit()
            .putString(KEY_POOL, toJson(_masterEnabled.value, _entries.value))
            .apply()
    }

    /**
     * Round-robin pick for batch member [index]: enabled entries only, in
     * pool order. Pure so the JVM pins hold; the instance wrapper reads the
     * current state. Master OFF, an empty pool, or all-disabled → null: the
     * caller keeps the standard resolve chain (byte-for-byte old behavior).
     */
    fun pickFor(index: Int): PoolEntry? =
        pickFor(index, _masterEnabled.value, _entries.value)

    internal fun pickFor(
        index: Int,
        masterEnabled: Boolean,
        entries: List<PoolEntry>,
    ): PoolEntry? {
        if (!masterEnabled) return null
        val enabled = entries.filter { it.enabled }
        if (enabled.isEmpty()) return null
        return enabled[index.mod(enabled.size)]
    }

    internal fun toJson(masterEnabled: Boolean, entries: List<PoolEntry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.modelEntryId)
                    .put("name", e.displayName)
                    .put("host", e.host)
                    .put("on", e.enabled),
            )
        }
        return JSONObject().put("on", masterEnabled).put("entries", arr).toString()
    }

    /** Tolerant parse: a corrupt file yields the OFF/empty state, never a crash. */
    internal fun parsePool(json: String?): Pair<Boolean, List<PoolEntry>> {
        if (json.isNullOrBlank()) return false to emptyList()
        return try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("entries") ?: JSONArray()
            val entries = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").trim()
                if (id.isEmpty()) return@mapNotNull null
                PoolEntry(
                    modelEntryId = id,
                    displayName = o.optString("name"),
                    host = o.optString("host"),
                    enabled = o.optBoolean("on", true),
                )
            }
            obj.optBoolean("on", false) to entries
        } catch (_: Exception) {
            false to emptyList()
        }
    }
}
