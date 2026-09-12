package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-payload-pairing-guard] Reported live, twice:
 *
 * ```
 * [400] TOOL_USE_RESULT_MISMATCH: unexpected `tool_use_id` found in
 * `tool_result` blocks: toolu_bdrk_011CJ… / toolu_bdrk_01G1Dk…
 * ```
 *
 * A compaction landed while the request was being built, so the payload
 * carried a `tool_result` whose `tool_use` had been sliced away. The guard is
 * the last check before the request leaves the app; these tests pin what it
 * accepts and what it strips.
 */
class PayloadPairingGuardTest {

    private fun user(text: String = "", parts: List<AgentContentPart> = emptyList()) =
        LLMMessage(role = LLMMessage.Role.USER, content = text, contentParts = parts)

    private fun assistant(text: String = "", parts: List<AgentContentPart> = emptyList()) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text, contentParts = parts)

    private fun use(id: String) = AgentContentPart.ToolUse(id, "shell_execute", JSONObject())
    private fun result(id: String) = AgentContentPart.ToolResult(id, "shell_execute", "ok")
    private fun text(s: String) = AgentContentPart.Text(s)

    /** The invariant the provider enforces. */
    private fun assertValid(messages: List<LLMMessage>) {
        val seenUses = HashSet<String>()
        for (msg in messages) {
            for (part in msg.contentParts) {
                if (part is AgentContentPart.ToolResult) {
                    assertTrue(
                        "tool_result ${part.id} has no preceding tool_use",
                        part.id in seenUses,
                    )
                }
            }
            // Uses become visible only to LATER messages.
            for (part in msg.contentParts) {
                if (part is AgentContentPart.ToolUse) seenUses.add(part.id)
            }
        }
        val allResults = messages.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>().map { it.id }.toSet()
        for (msg in messages) {
            for (part in msg.contentParts) {
                if (part is AgentContentPart.ToolUse) {
                    assertTrue("tool_use ${part.id} is unanswered", part.id in allResults)
                }
            }
        }
    }

    @Test
    fun `a well-formed payload passes through untouched`() {
        val msgs = listOf(
            user("run it"),
            assistant(parts = listOf(text("running"), use("t1"))),
            user(parts = listOf(result("t1"))),
            assistant("done"),
        )
        val out = PayloadPairingGuard.enforce(msgs)
        assertFalse(out.mutated)
        assertEquals(msgs, out.messages)
        assertValid(out.messages)
    }

    @Test
    fun `strips the exact reported shape - result whose use was sliced away`() {
        // What compaction-mid-request produced: the payload starts with the
        // tool_result because the assistant turn holding the call was cut.
        val msgs = listOf(
            user(parts = listOf(result("toolu_bdrk_011CJocdqTcZvH2oNT8QD1Nx"))),
            assistant("continuing"),
        )
        val out = PayloadPairingGuard.enforce(msgs)
        assertTrue(out.mutated)
        assertEquals(1, out.droppedResults)
        assertValid(out.messages)
        // The now-empty user shell is dropped, not sent as a contentless turn.
        assertEquals(1, out.messages.size)
    }

    @Test
    fun `keeps sibling results when only one is orphaned`() {
        val msgs = listOf(
            assistant(parts = listOf(use("t1"))),
            user(parts = listOf(result("t1"), result("orphan"))),
        )
        val out = PayloadPairingGuard.enforce(msgs)
        assertEquals(1, out.droppedResults)
        assertEquals(
            setOf("t1"),
            out.messages.flatMap { it.contentParts }
                .filterIsInstance<AgentContentPart.ToolResult>().map { it.id }.toSet(),
        )
        assertValid(out.messages)
    }

    @Test
    fun `drops an unanswered tool_use instead of sending it`() {
        // Sending it is the same 400 in the other direction ("tool_use without
        // tool_result"), so the call goes rather than the turn failing.
        val msgs = listOf(
            user("go"),
            assistant(parts = listOf(text("calling"), use("t1"))),
        )
        val out = PayloadPairingGuard.enforce(msgs)
        assertEquals(1, out.droppedUses)
        assertValid(out.messages)
        // Prose survives — only the call was invalid.
        assertTrue(out.messages.last().contentParts.any { it is AgentContentPart.Text })
    }

    @Test
    fun `a result appearing BEFORE its own use is invalid`() {
        // Position matters, not mere membership: the provider requires the call
        // in a PREVIOUS message.
        val msgs = listOf(
            user(parts = listOf(result("t1"))),
            assistant(parts = listOf(use("t1"))),
            user(parts = listOf(result("t1"))),
        )
        val out = PayloadPairingGuard.enforce(msgs)
        assertTrue(out.mutated)
        assertValid(out.messages)
    }

    @Test
    fun `a result in the SAME message as its use is invalid`() {
        val msgs = listOf(
            assistant(parts = listOf(use("t1"), result("t1"))),
        )
        val out = PayloadPairingGuard.enforce(msgs)
        assertTrue(out.mutated)
        assertValid(out.messages)
    }

    @Test
    fun `messages emptied by stripping are removed`() {
        val msgs = listOf(
            user("hello"),
            user(parts = listOf(result("gone"))),
            assistant("hi"),
        )
        val out = PayloadPairingGuard.enforce(msgs)
        assertEquals(listOf("hello", "hi"), out.messages.map { it.content })
    }

    @Test
    fun `a message keeping its text is not removed`() {
        val msgs = listOf(
            user(text = "still here", parts = listOf(result("gone"))),
        )
        val out = PayloadPairingGuard.enforce(msgs)
        assertEquals(1, out.messages.size)
        assertEquals("still here", out.messages[0].content)
    }

    @Test
    fun `plain conversations are untouched`() {
        val msgs = listOf(user("hi"), assistant("hello"), user("bye"))
        val out = PayloadPairingGuard.enforce(msgs)
        assertFalse(out.mutated)
        assertEquals(msgs, out.messages)
    }

    @Test
    fun `empty payload is handled`() {
        val out = PayloadPairingGuard.enforce(emptyList())
        assertFalse(out.mutated)
        assertTrue(out.messages.isEmpty())
    }

    @Test
    fun `a multi-call round survives intact`() {
        val msgs = listOf(
            assistant(parts = listOf(use("a"), use("b"), use("c"))),
            user(parts = listOf(result("a"), result("b"), result("c"))),
        )
        val out = PayloadPairingGuard.enforce(msgs)
        assertFalse(out.mutated)
        assertValid(out.messages)
    }
}
