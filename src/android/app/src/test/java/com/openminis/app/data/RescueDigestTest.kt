package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-session-rescue] Tests for the local, LLM-free digest builder.
 *
 * The contract that matters is not "output looks nice" but: the digest is
 * bounded, it never drops the identifiers/errors an agent needs to resume,
 * and it always preserves the user's most recent intent. Those are what these
 * tests pin down.
 */
class RescueDigestTest {

    private fun user(text: String) = RescueDigest.RescueTurn(
        role = RescueDigest.RescueTurn.Role.USER, text = text,
    )

    private fun assistant(text: String, tools: List<RescueDigest.RescueToolCall> = emptyList()) =
        RescueDigest.RescueTurn(
            role = RescueDigest.RescueTurn.Role.ASSISTANT, text = text, tools = tools,
        )

    private fun call(
        name: String,
        args: String = "",
        result: String = "",
        isError: Boolean = false,
    ) = RescueDigest.RescueToolCall(name, args, result, isError)

    @Test
    fun `empty input yields empty digest`() {
        assertEquals("", RescueDigest.build(emptyList()))
    }

    @Test
    fun `digest is bounded by the budget`() {
        // 200 turns of 2KB prose + 200 tool calls with 5KB results ≈ 1.4 MB in,
        // and it must still come out near the budget.
        val turns = (1..200).flatMap { i ->
            listOf(
                user("please do task number $i " + "u".repeat(2_000)),
                assistant(
                    "working on $i " + "a".repeat(2_000),
                    listOf(call("shell_execute", "echo $i", "o".repeat(5_000))),
                ),
            )
        }
        val budget = 12_000
        val digest = RescueDigest.build(turns, maxChars = budget)
        // Header/footer are fixed overhead outside the section shares; the
        // body must respect the budget.
        assertTrue(
            "digest was ${digest.length} chars for budget $budget",
            digest.length <= budget + 1_500,
        )
        assertTrue(digest.startsWith(RescueDigest.OPEN_TAG))
        assertTrue(digest.endsWith("</rescue-digest>"))
    }

    @Test
    fun `latest user intent always survives a tight budget`() {
        val turns = (1..50).map { user("old ask $it " + "x".repeat(3_000)) } +
            user("FINAL ASK: rebuild the parser")
        val digest = RescueDigest.build(turns, maxChars = 3_000)
        assertTrue(digest.contains("FINAL ASK: rebuild the parser"))
    }

    @Test
    fun `paths urls and hashes are preserved verbatim`() {
        val turns = listOf(
            user("check it"),
            assistant(
                "done",
                listOf(
                    call(
                        "shell_execute",
                        "sha256sum app.apk",
                        "b212fbc56c51520c8082c9d1254fee170250f847bef4a6cc80a0177fe89610d0  " +
                            "/var/minis/shared/build/app-clone.apk\n" +
                            "see https://github.com/acme/repo/actions/runs/123",
                    ),
                ),
            ),
        )
        val digest = RescueDigest.build(turns)
        assertTrue(digest.contains("/var/minis/shared/build/app-clone.apk"))
        assertTrue(digest.contains("https://github.com/acme/repo/actions/runs/123"))
        assertTrue(
            digest.contains("b212fbc56c51520c8082c9d1254fee170250f847bef4a6cc80a0177fe89610d0"),
        )
    }

    @Test
    fun `errors are kept even when successful calls are dropped`() {
        // 300 successful calls would flood the ledger; the single failure must
        // still make it in — re-attempting a known failure is the costly
        // mistake this section exists to prevent.
        val many = (1..300).map { call("shell_execute", "step $it", "ok") }
        val failing = call(
            "shell_execute", "gradlew assemble",
            "FAILED: Execution failed for task ':app:compileKotlin'", isError = true,
        )
        val turns = listOf(user("build it"), assistant("ok", many + failing))
        val digest = RescueDigest.build(turns, maxChars = 6_000)
        assertTrue(digest.contains("ERROR:"))
        assertTrue(digest.contains("gradlew assemble"))
        assertTrue(digest.contains("call(s) omitted"))
    }

    @Test
    fun `tool ledger reports name and identifying arg`() {
        val turns = listOf(
            user("read the file"),
            assistant("ok", listOf(call("file_read", "path=/etc/hosts", "127.0.0.1 localhost"))),
        )
        val digest = RescueDigest.build(turns)
        assertTrue(digest.contains("file_read"))
        assertTrue(digest.contains("/etc/hosts"))
    }

    @Test
    fun `last exchange is verbatim`() {
        val turns = listOf(
            user("first"),
            assistant("first reply"),
            user("what about the migration order?"),
            assistant("Run 7_8 before 8_9."),
        )
        val digest = RescueDigest.build(turns)
        assertTrue(digest.contains("what about the migration order?"))
        assertTrue(digest.contains("Run 7_8 before 8_9."))
    }

    @Test
    fun `clip keeps both ends and states the cut`() {
        val text = "HEAD".padEnd(500, 'm') + "TAIL"
        val clipped = RescueDigest.clip(text, 120)
        assertTrue(clipped.startsWith("HEAD"))
        assertTrue(clipped.endsWith("TAIL"))
        assertTrue(clipped.contains("chars cut"))
        assertTrue(clipped.length <= 120)
    }

    @Test
    fun `clip is a no-op below the limit`() {
        assertEquals("short", RescueDigest.clip("short", 100))
    }

    @Test
    fun `squeeze collapses newlines into a marker`() {
        assertEquals("a ⏎ b", RescueDigest.squeeze("a\n  b"))
        assertFalse(RescueDigest.squeeze("a\nb").contains("\n"))
    }

    @Test
    fun `digest states it is background not a work order`() {
        val digest = RescueDigest.build(listOf(user("hi"), assistant("hello")))
        assertTrue(digest.contains("PAST events"))
        assertTrue(digest.contains("not a pending task") || digest.contains("not as a work"))
    }

    @Test
    fun `tool-only history still produces a usable digest`() {
        // A session whose user turns were all offloaded/blank must not yield
        // an empty digest — the ledger alone is still worth sending.
        val turns = listOf(
            assistant("", listOf(call("shell_execute", "ls /var/minis", "attachments\nworkspace"))),
        )
        val digest = RescueDigest.build(turns)
        assertTrue(digest.contains("shell_execute"))
        assertTrue(digest.isNotBlank())
    }
}
