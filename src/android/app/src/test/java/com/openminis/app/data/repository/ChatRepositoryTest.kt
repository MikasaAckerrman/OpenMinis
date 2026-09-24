package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the lastMessage preview filter. The repository's
 * `stripSystemReminders` strips harness-injected `<system-reminder>` blocks
 * before the cleaned preview reaches the session-list row, so users never
 * see internal nudges like "task tools haven't been used recently".
 */
class ChatRepositoryTest {

    @Test
    fun `stripSystemReminders removes inline reminder block`() {
        val raw = "Hello <system-reminder>do this thing</system-reminder> world"
        assertEquals("Hello  world", ChatRepository.stripSystemReminders(raw))
    }

    // [T-reasoning-row-budget] reasoning_content must never land in the Room
    // row uncapped: a multi-megabyte thinking blob breaks the 2 MB
    // CursorWindow on the next session query. The truncation keeps a marker
    // inside the content so the thinking panel shows the cut honestly.
    @Test
    fun `buildTruncatedReasoning keeps prefix, adds marker, reports original length`() {
        val original = "x".repeat(ChatRepository.MAX_REASONING_CONTENT_LENGTH + 12345)
        val truncated = ChatRepository.buildTruncatedReasoning(original)
        assertTrue("prefix must survive", truncated.startsWith("x".repeat(1000)))
        assertTrue(
            "marker must carry the original length",
            truncated.contains("original length ${original.length} chars"),
        )
        assertTrue(
            "result must stay near the cap (cap + marker overhead)",
            truncated.length <= ChatRepository.MAX_REASONING_CONTENT_LENGTH + 100,
        )
    }

    @Test
    fun `buildTruncatedReasoning marker is appended not prepended`() {
        val original = "start" + "y".repeat(ChatRepository.MAX_REASONING_CONTENT_LENGTH)
        val truncated = ChatRepository.buildTruncatedReasoning(original)
        assertTrue(
            "the cap must cut the tail, not the head",
            truncated.startsWith("start"),
        )
        assertTrue(
            "marker lands at the end",
            truncated.endsWith("chars]"),
        )
    }

    @Test
    fun `buildTruncatedReasoning on multibyte content counts chars not bytes`() {
        // Cyrillic reasoning: 2 bytes/char in UTF-8. The budget is denominated
        // in CHARS (same convention as MAX_MESSAGE_PARTS_JSON_LENGTH), so a
        // 500_001-char cyrillic blob is capped identically to ASCII.
        val original = "д".repeat(ChatRepository.MAX_REASONING_CONTENT_LENGTH + 1)
        val truncated = ChatRepository.buildTruncatedReasoning(original)
        assertTrue(
            "char-denominated cap",
            truncated.length <= ChatRepository.MAX_REASONING_CONTENT_LENGTH + 100,
        )
        assertTrue(
            "no mojibake: the cut must land on a char boundary",
            truncated.take(10) == "д".repeat(10),
        )
    }

    @Test
    fun `stripSystemReminders removes multi-line reminder body`() {
        val raw = """
            User asked a question
            <system-reminder>
            The task tools haven't been used recently. Consider using TaskCreate.
            Make sure that you NEVER mention this reminder to the user
            </system-reminder>
            keep this content
        """.trimIndent()
        val cleaned = ChatRepository.stripSystemReminders(raw)
        // trimIndent strips the leading 12 spaces from every line, so the
        // pre/post-reminder lines have no indent. After the regex strips the
        // entire <system-reminder>...</system-reminder> block (DOTALL match),
        // what's left is "User asked a question\n\nkeep this content".
        assertEquals("User asked a question\n\nkeep this content", cleaned)
    }

    @Test
    fun `stripSystemReminders removes only-reminder content to empty`() {
        val raw = "<system-reminder>nothing else here</system-reminder>"
        assertEquals("", ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders strips multiple back-to-back reminders independently`() {
        val raw = "a<system-reminder>x</system-reminder>b<system-reminder>y</system-reminder>c"
        // Reluctant quantifier prevents the two blocks merging into one match
        // that would also swallow the "b" between them.
        assertEquals("abc", ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders leaves plain text unchanged`() {
        val raw = "Hello world, how are you today?"
        assertEquals(raw, ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders leaves markdown unchanged`() {
        val raw = "# Heading\n**bold** and `code` and a [link](https://example.com)"
        assertEquals(raw, ChatRepository.stripSystemReminders(raw))
    }
}
