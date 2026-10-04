package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * [T-webfetch] webfetch — the light "read this URL" path (ZCode port of
 * their webfetch handler). One HTTP GET, HTML stripped to clean markdown
 * by the same regex cascade they use, 10-minute response cache (repeat
 * fetches of the same URL in a session are free), hard char cap.
 *
 * Complements the tool family without overlap:
 * - web_search  = "find pages" (query → results)
 * - webfetch    = "read THIS page" (url → clean text, one call, no
 *   browser session, no JS execution — for static content)
 * - browser_use = "interact with a live page" (clicks, JS, screenshots)
 *
 * JS-heavy SPAs return a thin shell — the tool says so and points at
 * browser_use, mirroring their honest degraded-path contract.
 */
object WebFetchTool {

    const val NAME = "webfetch"
    private const val MAX_OUTPUT_CHARS = 15000
    private const val MAX_BODY_BYTES = 3 * 1024 * 1024
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private data class CacheEntry(val at: Long, val body: String)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Fetch ONE URL and return its content as clean readable text/markdown " +
            "(one call, no browser, no JS). For docs, articles, APIs, raw files. " +
            "JS-heavy apps (SPA dashboards) render thin — use browser_use for those. " +
            "Repeat fetches of the same URL within ~10 minutes are served from cache. " +
            "Prefer web_search when you don't have the URL yet.",
        parameters = mapOf(
            "url" to AgentToolParam(
                type = "string",
                description = "Absolute http(s) URL to fetch.",
            ),
            "max_length" to AgentToolParam(
                type = "integer",
                description = "Max characters returned (default 15000, max 60000).",
            ),
        ),
        required = listOf("url"),
    )

    suspend fun execute(argsJson: String): ToolExecutionResult {
        val args = runCatching { org.json.JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: malformed arguments", false)
        }
        val url = args.optString("url", "").trim()
        if (url.isEmpty()) return ToolExecutionResult("Error: 'url' is required", false)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolExecutionResult(
                "Error: 'url' must be http(s) — got: ${url.take(80)}", false)
        }
        val maxLength = args.optInt("max_length", MAX_OUTPUT_CHARS).coerceIn(1000, 60000)

        val key = "$url|$maxLength"
        val hit = cache[key]
        if (hit != null && System.currentTimeMillis() - hit.at < CACHE_TTL_MS) {
            return ToolExecutionResult(hit.body, true)
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,text/plain,*/*")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val mime = (response.header("Content-Type") ?: "").substringBefore(';')
                        .trim().lowercase()
                    val bytes = response.body?.bytes()
                        ?: return@withContext ToolExecutionResult("empty body", false)
                    if (bytes.size > MAX_BODY_BYTES) {
                        return@withContext ToolExecutionResult(
                            "response too large: ${bytes.size / 1024} KB (cap 3 MB)", false)
                    }
                    if (!response.isSuccessful) {
                        return@withContext ToolExecutionResult(
                            "HTTP ${response.code} for $url", false)
                    }
                    val text = bytes.toString(Charsets.UTF_8)
                    val body = when {
                        mime.contains("html") -> htmlToMarkdown(text)
                        else -> text.trim()
                    }
                    val capped = if (body.length > maxLength) {
                        body.take(maxLength) + "\n…(truncated, ${body.length - maxLength} more chars — refetch with max_length)"
                    } else body
                    val thin = body.length < 400 && mime.contains("html")
                    val note = if (thin) {
                        "\n\n[note: this page looks JS-rendered (thin static shell) — use browser_use for the live version]"
                    } else ""
                    cache[key] = CacheEntry(System.currentTimeMillis(), capped + note)
                    ToolExecutionResult(capped + note, true)
                }
            }.getOrElse { e ->
                ToolExecutionResult(
                    "webfetch network error: ${e.message?.take(200)}\n" +
                        "Check connectivity; for live/JS pages use browser_use.", false)
            }
        }
    }

    /**
     * ZCode's htmlToMarkdown cascade, ported verbatim in spirit: strip
     * comments/scripts/styles, map headings/links/lists to markdown,
     * drop remaining tags, decode entities, squeeze blank runs.
     */
    private fun htmlToMarkdown(html: String): String {
        var c = html
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
            .replace(Regex("<script\\b[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style\\b[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<noscript\\b[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), "")
        c = Regex("<h1\\b[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE).replace(c) { "\n# ${it.groupValues[1].trim()}\n" }
        c = Regex("<h2\\b[^>]*>([\\s\\S]*?)</h2>", RegexOption.IGNORE_CASE).replace(c) { "\n## ${it.groupValues[1].trim()}\n" }
        c = Regex("<h3\\b[^>]*>([\\s\\S]*?)</h3>", RegexOption.IGNORE_CASE).replace(c) { "\n### ${it.groupValues[1].trim()}\n" }
        c = Regex("<h([456])\\b[^>]*>([\\s\\S]*?)</h\\1>", RegexOption.IGNORE_CASE).replace(c) { "\n#### ${it.groupValues[2].trim()}\n" }
        c = Regex("<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE).replace(c) { "[${it.groupValues[2].trim()}](${it.groupValues[1]})" }
        c = Regex("<li\\b[^>]*>([\\s\\S]*?)</li>", RegexOption.IGNORE_CASE).replace(c) { "\n- ${it.groupValues[1].trim()}" }
        c = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).replace(c, "\n")
        c = Regex("</(?:p|div|section|article|header|footer|tr|table|ul|ol)>", RegexOption.IGNORE_CASE).replace(c, "\n")
        c = c.replace(Regex("<[^>]+>"), "")
        c = decodeEntities(c)
        return c.split('\n').joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&hellip;", "…")
}
