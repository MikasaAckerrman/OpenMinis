package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentToolDefinition
import org.json.JSONArray

/**
 * [Э4-round-cost] Per-round tool-schema serialization eliminated. The
 * measured round turnaround (vc167, 13.11) was median 2.18s BEFORE the
 * "→ REQ" log — inside that window the provider serialized all 32 tool
 * schemas TWICE (RequestBudget.estimateOverheadBytes counts their bytes,
 * then the body builds the tools array again), plus RequestBudget.plan
 * re-measured every history message. On a 248KB body that is real phone
 * CPU paid on every round of every session.
 *
 * This cache serializes each schema exactly once per DISTINCT tool list.
 * The key is the list itself: [List.equals] walks element-wise through the
 * data classes — exact content equality, no hash collisions, and the
 * compare cost (string memcmp over ~64KB of descriptions) is orders of
 * magnitude below the serialization it gates. Any param change rebuilds;
 * identical rebuilds of the same list (the engine re-creates the list every
 * round) hit.
 *
 * The cached JSONArray is shared read-only across request bodies: nothing
 * mutates it after build, and org.json toString walks the graph without
 * writing to it — concurrent bodies holding the same array are safe.
 */
object ToolWireCache {

    data class Wire(
        /** Shared read-only array for body.put("tools", ...). */
        val schemas: JSONArray,
        /** Sum of per-schema serialized UTF-8 bytes (RequestBudget overhead). */
        val totalBytes: Int,
    )

    @Volatile
    private var cachedFor: List<AgentToolDefinition>? = null

    @Volatile
    private var cachedWire: Wire? = null

    @Synchronized
    fun wire(tools: List<AgentToolDefinition>): Wire {
        val cached = cachedWire
        val key = cachedFor
        if (cached != null && key == tools) return cached
        val arr = JSONArray()
        var bytes = 0
        tools.forEach { t ->
            val schema = t.toOpenAIJson()
            bytes += schema.toString().toByteArray().size
            arr.put(schema)
        }
        val w = Wire(arr, bytes)
        cachedFor = tools
        cachedWire = w
        return w
    }
}
