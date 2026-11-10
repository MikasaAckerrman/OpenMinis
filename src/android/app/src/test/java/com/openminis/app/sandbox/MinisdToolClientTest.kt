package com.openminis.app.sandbox

import java.net.ServerSocket
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [M-R3.3] The loopback client contract for the workspace-rooted engine
 * tools. Two layers:
 *
 *  - the PURE layer (envelope parse, payload extraction, error-envelope
 *    degradation) is pinned exactly — a wrong parse here would silently
 *    downgrade searches to the rg fallback or worse, surface a wrong
 *    answer;
 *  - the WIRE layer runs once against a real loopback ServerSocket: the
 *    same request shape TelegramProfileSync's gate already accepts in
 *    production (POST /mcp, byte-exact Content-Length, bearer header)
 *    must round-trip.
 */
class MinisdToolClientTest {

    /** A canned HTTP 200 response carrying [text] as result.content[0].text. */
    private fun envelopeWith(text: String): String =
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" +
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 7)
                .put(
                    "result",
                    JSONObject().put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "text").put("text", text)),
                    ),
                )
                .toString()

    @Test
    fun `envelope parse walks the headers to result content text`() {
        val body = envelopeWith("""{"matches":[],"truncated":false}""")
        assertEquals(
            """{"matches":[],"truncated":false}""",
            MinisdToolClient.extractContentText(body),
        )
    }

    @Test
    fun `envelope parse rejects non-envelope shapes`() {
        assertNull(MinisdToolClient.extractContentText("HTTP/1.1 500 X\r\n\r\nnot json"))
        assertNull(MinisdToolClient.extractContentText("""{"result":{"content":[]}}"""))
        assertNull(MinisdToolClient.extractContentText("""{"result":{}}"""))
        assertNull(MinisdToolClient.extractContentText("garbage"))
    }

    @Test
    fun `success envelope parses into the payload object`() {
        val body = envelopeWith(
            """{"matches":[{"file":"a.kt","line":1,"text":"x"}],"truncated":false}""",
        )
        val out = MinisdToolClient.toolsCallVia({ _, _ -> body }, "grep", JSONObject())
        assertEquals(1, out!!.optJSONArray("matches").length())
        assertEquals("a.kt", out.optJSONArray("matches").optJSONObject(0)?.optString("file"))
    }

    @Test
    fun `business error envelope degrades to null like a transport failure`() {
        // §5.1: the error rides a SUCCESS envelope ({"error": ...}); the
        // caller's fallback must treat it exactly like a dead daemon —
        // never surface a half answer.
        val body = envelopeWith("""{"error":"minisd error: workspace root is not configured"}""")
        assertNull(MinisdToolClient.toolsCallVia({ _, _ -> body }, "grep", JSONObject()))
    }

    @Test
    fun `transport failure and malformed text degrade to null`() {
        assertNull(MinisdToolClient.toolsCallVia({ _, _ -> null }, "grep", JSONObject()))
        assertNull(
            MinisdToolClient.toolsCallVia({ _, _ -> envelopeWith("not-json") }, "grep", JSONObject()),
        )
    }

    @Test
    fun `raw wire carries bearer, path and content-length exactly`() {
        // One-shot loopback gate: pins the request shape the bearer check
        // validates and proves the transport round-trip on a real socket.
        val server = ServerSocket(0)
        val seen = StringBuilder()
        thread(name = "minisd-fake-gate") {
            server.accept().use { client ->
                val reader = client.getInputStream().bufferedReader()
                val headers = StringBuilder()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) {
                        headers.append('\n') // the blank line that ends the headers
                        break
                    }
                    if (line.startsWith("Content-Length:")) {
                        contentLength = line.substringAfter(":").trim().toInt()
                    }
                    headers.append(line).append('\n')
                }
                val bodyChars = CharArray(contentLength)
                var off = 0
                while (off < contentLength) {
                    val n = reader.read(bodyChars, off, contentLength - off)
                    if (n < 0) break
                    off += n
                }
                seen.append(headers).append(String(bodyChars, 0, off))
                val resp = envelopeWith("""{"ok":true}""")
                val respBytes = resp.toByteArray(Charsets.UTF_8)
                client.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${respBytes.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.UTF_8),
                    )
                    write(respBytes)
                    flush()
                }
            }
            server.close()
        }
        try {
            // The RESPONSE side goes through the real envelope: the raw
            // body carries JSON-escaped inner quotes, so string-matching
            // it would pin an implementation detail — parse it instead.
            val payload = MinisdToolClient.toolsCallVia(
                { tool, argsJson ->
                    MinisdToolClient.rawCall("test-secret", tool, argsJson, port = server.localPort)
                },
                "grep",
                JSONObject("""{"pattern":"needle"}"""),
            )!!
            assertTrue(payload.optBoolean("ok"))

            val request = seen.toString()
            val wireBody = request.substringAfter("\n\n")
            assertTrue(request.startsWith("POST /mcp HTTP/1.1\n"))
            assertTrue(request.contains("Authorization: Bearer test-secret\n"))
            assertTrue(request.contains("Connection: close\n"))
            assertTrue(request.contains("Content-Length: ${wireBody.toByteArray(Charsets.UTF_8).size}\n"))
            assertTrue(wireBody.contains("\"name\":\"grep\""))
            assertTrue(wireBody.contains("\"pattern\":\"needle\""))
        } finally {
            server.close()
        }
    }
}
