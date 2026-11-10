package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import java.net.Socket
import org.json.JSONObject

/**
 * [M-R3.3] The loopback `tools/call` client for the workspace-rooted
 * engine tools (grep/glob) served by the rust daemon on 127.0.0.1:7824.
 *
 * Why a client and not a fork: every [com.openminis.app.sandbox.NativeSearch]
 * call used to spawn an rg process (the fork/pipe class that produced the
 * B45 stderr deadlock); the daemon keeps the same search logic alive in
 * memory and answers over one socket hop, no child process, no pipes.
 *
 * The transport mirrors [TelegramProfileSync.vaultToolCall] — the same
 * authenticated loopback gate (bearer from the vault auth.key, byte-exact
 * Content-Length, Connection: close) the profile sync and the liveness
 * probe already run in production. Every failure returns null — the
 * caller falls back to its local path; this class never throws and never
 * surfaces a HALF answer: a business error envelope (`{"error": ...}`,
 * e.g. the daemon running without a workspace) is indistinguishable from
 * a transport failure to the caller, which is exactly what the fallback
 * wants.
 */
object MinisdToolClient {
    private const val TAG = "MinisdToolClient"
    private const val PORT = 7824
    private const val TIMEOUT_MS = 3_000

    /**
     * The transport's last failure, "class: message" — a diagnostic the
     * same way PersistentShell.startFailure is: production logs the same
     * line, and the wire test asserts WITH this in the message so a red
     * CI run names its cause instead of a bare NPE.
     */
    @Volatile internal var lastFailure: String? = null

    /**
     * Blocking tools/call against the live daemon. Returns the parsed MCP
     * payload (the `content[0].text` JSON object), or null on transport
     * failure / unparseable answer / business error envelope. Call from
     * Dispatchers.IO — this does blocking socket I/O.
     */
    fun toolsCall(context: Context, tool: String, arguments: JSONObject): JSONObject? {
        val secret = TelegramVaultManager.authSecret(context)
        return toolsCallVia({ t, argsJson -> rawCall(secret, t, argsJson) }, tool, arguments)
    }

    /**
     * The injectable core: [transport] maps (tool, argsJson) to the raw
     * HTTP response body (null = transport failure); everything past the
     * socket is pure parsing, pinned by unit tests. One implementation
     * serves both the context path above and the scripted test path.
     */
    internal fun toolsCallVia(
        transport: (tool: String, argsJson: String) -> String?,
        tool: String,
        arguments: JSONObject,
    ): JSONObject? {
        val body = transport(tool, arguments.toString()) ?: return null
        val text = extractContentText(body) ?: return null
        val payload = runCatching { JSONObject(text) }.getOrNull() ?: return null
        // §5.1: a business error still rides a SUCCESS envelope at the
        // JSON-RPC level — the error object is the payload itself.
        if (payload.has("error")) return null
        return payload
    }

    /**
     * The MCP envelope: the payload rides `result.content[0].text`
     * (serialized by the rust server). Tolerates a headerless body (the
     * same lenient split TelegramProfileSync uses). Null when the shape
     * is not the envelope at all.
     */
    internal fun extractContentText(httpBody: String): String? {
        val body = httpBody.substringAfter("\r\n\r\n", "")
            .ifBlank { httpBody.substringAfter("\n\n", httpBody) }
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val content = json.optJSONObject("result")?.optJSONArray("content") ?: return null
        if (content.length() == 0) return null
        val text = content.optJSONObject(0)?.optString("text")
        return text?.takeIf { it.isNotBlank() }
    }

    /**
     * The wire: POST /mcp with the bearer header when armed. Mirrors
     * TelegramProfileSync byte-for-byte (the gate checks exact shapes,
     * not intentions). [port] exists for the loopback transport test.
     */
    internal fun rawCall(secret: String, tool: String, argsJson: String, port: Int = PORT): String? =
        try {
            val authHeader = secret.takeIf { it.isNotEmpty() }
                ?.let { "Authorization: Bearer $it\r\n" } ?: ""
            val body = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 7)
                .put("method", "tools/call")
                .put(
                    "params",
                    JSONObject()
                        .put("name", tool)
                        .put("arguments", JSONObject(argsJson)),
                )
                .toString()
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = TIMEOUT_MS
                val request = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Content-Type: application/json\r\n" +
                    authHeader +
                    "Connection: close\r\n" +
                    "Content-Length: ${bodyBytes.size}\r\n\r\n"
                s.getOutputStream().apply {
                    write(request.toByteArray(Charsets.UTF_8))
                    write(bodyBytes)
                    flush()
                }
                s.getInputStream().bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            lastFailure = "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "minisd tools/call($tool) failed: ${e.message}")
            null
        }
}
