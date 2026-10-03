package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.EnvVarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [T-web-search] First-class web search — closes the "search = expensive
 * WebView navigation" gap from the ZCode comparison. Direct Tavily API
 * call from the app (no sandbox round-trip, no browser session): the
 * model gets clean {answer, title, url, snippet} results in one tool
 * call. The key comes from the app's envvar store (TAVILY_API_KEY,
 * Settings → Environments) — never logged, never echoed.
 *
 * Fallback contract: when the key is absent or the call fails hard, the
 * tool says so explicitly and points the model at browser_use — a
 * degraded path is better than a lie.
 */
object WebSearchTool {

    const val NAME = "web_search"
    private const val KEY_ENV = "TAVILY_API_KEY"
    private const val ENDPOINT = "https://api.tavily.com/search"
    private const val MAX_OUTPUT_CHARS = 12000
    private const val SNIPPET_CAP = 600

    private val json = "application/json; charset=utf-8".toMediaType()

    // Client is lazy and shared; search calls are short-lived.
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search the web with a search API and get clean structured results " +
            "(titles, URLs, snippets + a synthesized answer). FAST and cheap — one call, " +
            "no page loading. Use this INSTEAD OF browser_use whenever you just need " +
            "facts/links/research, not a live page session. Supports domain filters " +
            "(include/exclude) and depth: 'advanced' re-ranks deeper (slower, for hard " +
            "questions). Depth 'basic' is right for most queries.",
        parameters = mapOf(
            "query" to AgentToolParam(
                type = "string",
                description = "The search query (natural language works).",
            ),
            "max_results" to AgentToolParam(
                type = "integer",
                description = "How many results to return (default 5, max 10).",
            ),
            "depth" to AgentToolParam(
                type = "string",
                description = "'basic' (default, fast) or 'advanced' (deeper re-ranking).",
                enumValues = listOf("basic", "advanced"),
            ),
            "include_domains" to AgentToolParam(
                type = "string",
                description = "Comma-separated domain allowlist, e.g. 'docs.oracle.com,stackoverflow.com'. Optional.",
            ),
            "exclude_domains" to AgentToolParam(
                type = "string",
                description = "Comma-separated domain blocklist. Optional.",
            ),
        ),
        required = listOf("query"),
    )

    suspend fun execute(argsJson: String, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: malformed arguments", false)
        }
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) {
            return ToolExecutionResult("Error: 'query' is required", false)
        }
        val key = EnvVarRepository(context).getValue(KEY_ENV)
        if (key.isNullOrBlank()) {
            return ToolExecutionResult(
                "web_search is not configured: no TAVILY_API_KEY in the app environments " +
                    "(Settings → Environments). Use browser_use as the fallback for this query.",
                false)
        }
        val maxResults = args.optInt("max_results", 5).coerceIn(1, 10)
        val depth = if (args.optString("depth", "basic") == "advanced") "advanced" else "basic"
        val body = JSONObject().apply {
            put("query", query)
            put("max_results", maxResults)
            put("search_depth", depth)
            put("include_answer", true)
            args.optString("include_domains", "").trim().takeIf { it.isNotEmpty() }?.let {
                put("include_domains", JSONArray(it.split(',').map { d -> d.trim() }.filter { d -> d.isNotEmpty() }))
            }
            args.optString("exclude_domains", "").trim().takeIf { it.isNotEmpty() }?.let {
                put("exclude_domains", JSONArray(it.split(',').map { d -> d.trim() }.filter { d -> d.isNotEmpty() }))
            }
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(json))
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        return@withContext ToolExecutionResult(
                            "web_search failed: HTTP ${response.code} — ${text.take(300)}\n" +
                                "Use browser_use as the fallback for this query.",
                            false)
                    }
                    val parsed = parseResults(JSONObject(text))
                    ToolExecutionResult(parsed, true)
                }
            }.getOrElse { e ->
                ToolExecutionResult(
                    "web_search network error: ${e.message?.take(200)}\n" +
                        "Use browser_use as the fallback for this query.",
                    false)
            }
        }
    }

    /**
     * Compact, model-friendly rendering, hard-capped to protect the context
     * window: synthesized answer first, then numbered results with title,
     * URL and a trimmed snippet.
     */
    private fun parseResults(root: JSONObject): String {
        val sb = StringBuilder()
        root.optString("answer", "").trim().takeIf { it.isNotEmpty() }?.let {
            sb.append("ANSWER: ").append(it.trim()).append("\n\n")
        }
        val results = root.optJSONArray("results") ?: JSONArray()
        var i = 0
        while (i < results.length() && sb.length < MAX_OUTPUT_CHARS) {
            val r = results.optJSONObject(i) ?: continue
            val title = r.optString("title", "").trim()
            val url = r.optString("url", "").trim()
            val snippet = r.optString("content", "").trim()
            sb.append(i + 1).append(". ").append(title.ifEmpty { "(untitled)" })
            if (url.isNotEmpty()) sb.append("\n   ").append(url)
            if (snippet.isNotEmpty()) {
                val capped = if (snippet.length > SNIPPET_CAP) snippet.take(SNIPPET_CAP) + "…" else snippet
                sb.append("\n   ").append(capped.replace('\n', ' '))
            }
            sb.append("\n\n")
            i++
        }
        if (sb.isEmpty()) return "no results for the query"
        return if (sb.length > MAX_OUTPUT_CHARS) sb.take(MAX_OUTPUT_CHARS) + "\n…(truncated)" else sb.toString().trim()
    }
}
