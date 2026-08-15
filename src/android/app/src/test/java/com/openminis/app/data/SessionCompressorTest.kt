package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [session-longpress-compress] Pins the on-disk `parts_json` → RescueTurn
 * adaptation and the LLM-free whole-session digest. See [SessionCompressor].
 */
class SessionCompressorTest {

    private fun row(role: String, parts: String) = SessionCompressor.Row(role, parts)

    private val sampleRows = listOf(
        row("user", """[{"type":"text","value":"Собери APK и найди логи ошибок"}]"""),
        row(
            "assistant",
            """[{"type":"text","value":"Запускаю сборку"},{"type":"toolUse","value":{"toolUseId":"t1","name":"shell_execute","input":"{\"command\":\"./gradlew assembleRelease\"}","description":"Build APK"}}]""",
        ),
        row(
            "user",
            """[{"type":"toolResult","value":{"toolUseId":"t1","name":"shell_execute","output":"BUILD SUCCESSFUL in 3m","success":true}}]""",
        ),
        row("assistant", """[{"type":"text","value":"Сборка прошла успешно, APK готов."}]"""),
    )

    @Test
    fun `digest is a well-formed rescue digest`() {
        val digest = SessionCompressor.buildDigest(sampleRows, maxChars = 4000)
        assertTrue(digest.isNotBlank())
        assertTrue(digest.startsWith(RescueDigest.OPEN_TAG))
        assertTrue(digest.trimEnd().endsWith("</rescue-digest>"))
    }

    @Test
    fun `digest preserves user intent tool command and outcome`() {
        val digest = SessionCompressor.buildDigest(sampleRows, maxChars = 4000)
        assertTrue("user intent", digest.contains("Собери APK"))
        assertTrue("tool command verbatim", digest.contains("gradlew assembleRelease"))
        assertTrue("tool outcome", digest.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun `tool-result-only row is folded into its tool_use, not a standalone turn`() {
        val turns = SessionCompressor.toTurns(sampleRows)
        assertEquals(3, turns.size)
        assertTrue(
            turns.any { t ->
                t.tools.any { it.name == "shell_execute" && it.result.contains("BUILD SUCCESSFUL") }
            },
        )
    }

    @Test
    fun `failed tool result is flagged as error`() {
        val errRows = listOf(
            row("user", """[{"type":"text","value":"deploy"}]"""),
            row("assistant", """[{"type":"toolUse","value":{"toolUseId":"e1","name":"shell_execute","input":"{\"command\":\"kubectl apply\"}"}}]"""),
            row("user", """[{"type":"toolResult","value":{"toolUseId":"e1","name":"shell_execute","output":"error: connection refused","success":false}}]"""),
        )
        val turns = SessionCompressor.toTurns(errRows)
        assertTrue(turns.any { t -> t.tools.any { it.isError } })
    }

    @Test
    fun `empty and malformed input is safe`() {
        assertEquals("", SessionCompressor.buildDigest(emptyList()))
        assertTrue(SessionCompressor.toTurns(listOf(row("user", "not json"))).isEmpty())
        assertTrue(SessionCompressor.toTurns(listOf(row("user", """[{"type":"text","value":""}]"""))).isEmpty())
    }
}
