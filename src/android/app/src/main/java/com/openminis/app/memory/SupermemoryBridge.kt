package com.openminis.app.memory

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * [T-supermemory] Bridge to the locally running supermemory server
 * (github.com/supermemoryai/supermemory, installed in the PRoot sandbox
 * 21.09.2026). Design rule: the bridge must NEVER be load-bearing —
 * every call degrades silently (null / emptyList) when the server is
 * down, slow or answers garbage. The memory system (daily logs +
 * GLOBAL.md) remains the source of truth; supermemory is the
 * long-term associative layer on top.
 *
 * Blocking BY DESIGN (called only from background threads — the
 * distiller's IO coroutine and the prompt builder inside the stream
 * job): a suspend signature would exclude the non-suspend prompt
 * builder, the natural injection point. Every call is bounded at 3.5s
 * by the HTTP timeouts, so a dead server can not stall a turn.
 *
 * WRITE path: TurnMemoryDistiller pushes each distilled exchange in.
 * READ path: buildInjection() shapes the top matches into a compact
 * context block for the next turn.
 */
object SupermemoryBridge {
    /** Default local port of the sandbox supermemory server. */
    const val DEFAULT_PORT = 6333

    private const val TIMEOUT_MS = 3500

    /** Hard caps: the bridge is an enhancement, never a context eater. */
    const val MAX_INJECTED = 3
    private const val MAX_SNIPPET_CHARS = 220

    data class Hit(val id: String, val content: String, val score: Double)

    /** Fire-and-forget ingest; true only on a confirmed 2xx. */
    fun add(content: String, port: Int = DEFAULT_PORT): Boolean {
        if (content.isBlank()) return false
        return runCatching {
            val conn = (URL("http://127.0.0.1:$port/api/add").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(JSONObject().put("content", content).toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }

    /** Associative search; empty list on any failure (server down etc). */
    fun search(query: String, port: Int = DEFAULT_PORT): List<Hit> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            val conn = (URL("http://127.0.0.1:$port/api/search?q=" +
                java.net.URLEncoder.encode(query.take(400), "UTF-8")).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()
            // Tolerant parsing: [ {id, content, score?}, ... ] or {results: [...]}.
            val arr: JSONArray = when {
                body.trimStart().startsWith("[") -> JSONArray(body)
                else -> JSONObject(body).optJSONArray("results") ?: JSONArray()
            }
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Hit(
                    id = o.optString("id", i.toString()),
                    content = o.optString("content", o.optString("text", "")),
                    score = o.optDouble("score", 0.0),
                )
            }.filter { it.content.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /**
     * Pure shaping of the injection block: top [MAX_INJECTED] snippets,
     * each truncated, each one line — a quiet "relevant long-term
     * memories" preamble for the next turn. Null when there is nothing
     * worth injecting (no hits → no noise in context).
     */
    fun buildInjection(hits: List<Hit>): String? {
        val picked = hits.take(MAX_INJECTED)
        if (picked.isEmpty()) return null
        val lines = picked.joinToString("\n") { h ->
            val one = h.content.replace('\n', ' ').trim()
            "• ${if (one.length > MAX_SNIPPET_CHARS) one.take(MAX_SNIPPET_CHARS) + "…" else one}"
        }
        return "Релевантные долгосрочные воспоминания (supermemory):\n$lines"
    }
}
