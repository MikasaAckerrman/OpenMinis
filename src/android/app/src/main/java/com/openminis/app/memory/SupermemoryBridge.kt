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
    // [T-supermemory-port-fix] 6767, NOT 6333: the deployed local server
    // (run.sh, README_SETUP «веб-морда на 6767») has always listened on 6767 —
    // verified live 24.09: POST /v3/documents 200, POST /v3/search 200. The
    // bridge shipped with 6333, so EVERY distilled turn since then failed
    // silently (circuit breaker opened, zero distills ever reached the
    // store). The compact path (8a0a961) hardcoded the correct 6767 inline —
    // which masked the divergence until the user's «supermemory после
    // первого "сжать"» report (compact ingest was the only working path,
    // and even that just enqueued while the server was down).
    const val DEFAULT_PORT = 6767

    private const val TIMEOUT_MS = 3500

    /** Hard caps: the bridge is an enhancement, never a context eater. */
    const val MAX_INJECTED = 3
    private const val MAX_SNIPPET_CHARS = 220

    data class Hit(val id: String, val content: String, val score: Double)

    /**
     * [T-supermemory-perf] Circuit breaker: a dead/slow server must cost
     * NOTHING after the first two failures — 10 minutes of fast skips
     * instead of a 3.5s timeout on every prompt build (auto-mode runs
     * would pay it 500×). Success resets the counter. Thread-safe via
     * atomics; `nowMs` is a parameter so the logic is unit-testable.
     */
    private val breakerFailures = java.util.concurrent.atomic.AtomicInteger(0)
    private val breakerOpenedAtMs = java.util.concurrent.atomic.AtomicLong(0)

    private const val BREAKER_THRESHOLD = 2
    private const val BREAKER_OPEN_MS = 10L * 60 * 1000

    private fun breakerIsOpen(nowMs: Long): Boolean {
        val openedAt = breakerOpenedAtMs.get()
        if (openedAt == 0L) return false
        if (nowMs - openedAt >= BREAKER_OPEN_MS) {
            // Half-open: allow ONE probe to re-check the server.
            breakerOpenedAtMs.set(0)
            breakerFailures.set(0)
            return false
        }
        return true
    }

    private fun recordFailure(nowMs: Long) {
        if (breakerFailures.incrementAndGet() >= BREAKER_THRESHOLD) {
            breakerOpenedAtMs.compareAndSet(0, nowMs)
        }
    }

    private fun recordSuccess() {
        breakerFailures.set(0)
        breakerOpenedAtMs.set(0)
    }

    /**
     * [T-supermemory-perf] Query cache: within one agent loop the LAST USER
     * MESSAGE does not change, so the identical search would re-run on
     * every iteration. Small bounded LRU with TTL — one network search per
     * user message, the rest are memory hits.
     */
    private val queryCache = object {
        private val lock = Any()
        private val map = LinkedHashMap<String, Pair<Long, List<Hit>>>(8, 0.75f, true)
        fun get(query: String, nowMs: Long): List<Hit>? = synchronized(lock) {
            val e = map[query] ?: return null
            if (nowMs - e.first > BREAKER_OPEN_MS) { map.remove(query); null } else e.second
        }
        fun put(query: String, hits: List<Hit>, nowMs: Long) = synchronized(lock) {
            map[query] = nowMs to hits
            if (map.size > 8) map.remove(map.keys.first())
        }
    }

    /** Fire-and-forget ingest; true only on a confirmed 2xx. */
    fun add(content: String, port: Int = DEFAULT_PORT): Boolean {
        if (content.isBlank()) return false
        val now = System.currentTimeMillis()
        // Fast fail while the breaker is open (fire-and-forget, but no
        // point paying timeouts into a dead server 500 times).
        if (breakerIsOpen(now)) return false
        val ok = runCatching {
            // [T-supermemory-api-fix] REAL endpoint: POST /v3/documents with
            // {"content": ...} — verified live (24.09, 200 {"id","status":"queued"}).
            // The old /api/add never existed on the deployed server.
            val conn = (URL("http://127.0.0.1:$port/v3/documents").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(JSONObject().put("content", content).toString().toByteArray()) }
            val ok2 = conn.responseCode in 200..299
            conn.disconnect()
            ok2
        }.getOrDefault(false)
        if (ok) recordSuccess() else recordFailure(now)
        return ok
    }

    /** Associative search; empty list on any failure (server down etc). */
    fun search(query: String, port: Int = DEFAULT_PORT): List<Hit> {
        if (query.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        // Fast path: cached answer for the same query within TTL.
        queryCache.get(query, now)?.let { return it }
        // Fast fail: breaker open after repeated failures — skip the
        // 3.5s timeout entirely.
        if (breakerIsOpen(now)) return emptyList()
        val hits = runCatching {
            // [T-supermemory-api-fix] REAL endpoint: POST /v3/search with a
            // JSON body {"q": ...} — verified live (24.09, 200). The old
            // GET /api/search?q= never existed. Response shape (captured
            // live): {"results":[{"documentId","score","title","chunks":
            // [{"content","score","isRelevant","position"}]}],"timing","total"}
            // — chunks are NESTED inside each result, one chunk per hit.
            val conn = (URL("http://127.0.0.1:$port/v3/search").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use {
                it.write(JSONObject().put("q", query.take(400)).toString().toByteArray())
            }
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()
            val results = JSONObject(body).optJSONArray("results") ?: JSONArray()
            (0 until results.length()).flatMap { ri ->
                val r = results.optJSONObject(ri) ?: return@flatMap emptyList()
                val docId = r.optString("documentId", ri.toString())
                val chunks = r.optJSONArray("chunks") ?: JSONArray()
                (0 until chunks.length()).mapNotNull { ci ->
                    val c = chunks.optJSONObject(ci) ?: return@mapNotNull null
                    // isRelevant=false = the server's memory agent judged the
                    // chunk noise — respect its judgement, don't inject it.
                    if (c.optBoolean("isRelevant", true) == false) return@mapNotNull null
                    Hit(
                        id = docId,
                        content = c.optString("content", ""),
                        score = c.optDouble("score", 0.0),
                    )
                }
            }.filter { it.content.isNotBlank() }
        }.getOrElse {
            recordFailure(now)
            return emptyList()
        }
        recordSuccess()
        queryCache.put(query, hits, now)
        return hits
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
