package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-request-byte-budget] Pins the provider-boundary byte gate: old, large
 * tool_results are elided (their id preserved) until the body fits, while the
 * freshest user-text turns and everything after them are sent verbatim.
 */
class RequestBudgetTest {

    private fun user(text: String) = LLMMessage(LLMMessage.Role.USER, text)

    private fun asstToolUse(id: String) = LLMMessage(
        LLMMessage.Role.ASSISTANT, "",
        contentParts = listOf(AgentContentPart.ToolUse(id, "shell_execute", org.json.JSONObject())),
    )

    private fun userToolResult(id: String, chars: Int, name: String = "shell_execute", path: String? = null) =
        LLMMessage(
            LLMMessage.Role.USER, "",
            contentParts = listOf(
                AgentContentPart.ToolResult(id, name, "x".repeat(chars), imageLinuxPath = path),
            ),
        )

    private fun toolResultContents(msgs: List<LLMMessage>): List<String> =
        msgs.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().map { it.content }

    private fun toolResultIds(msgs: List<LLMMessage>): Set<String> =
        msgs.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().map { it.id }.toSet()

    @Test
    fun `under-ceiling body is returned unchanged`() {
        val msgs = listOf(user("hi"), asstToolUse("a"), userToolResult("a", 500), user("thanks"))
        val r = RequestBudget.plan(msgs, protectRecentUserTextTurns = 6, maxBodyBytes = 300_000)
        assertEquals(0, r.elidedToolResultCount)
        assertSame(msgs, r.messages)
    }

    @Test
    fun `elides oldest-largest tool_results until body fits, keeps ids`() {
        val msgs = ArrayList<LLMMessage>()
        // 20 old rounds, each a 40k tool_result => ~800k body
        for (n in 1..20) {
            msgs.add(user("q$n")); msgs.add(asstToolUse("t$n")); msgs.add(userToolResult("t$n", 40_000))
        }
        // 6 fresh user turns
        for (n in 1..6) msgs.add(user("fresh $n"))

        val before = RequestBudget.estimateBytes(msgs)
        val r = RequestBudget.plan(msgs, protectRecentUserTextTurns = 6, maxBodyBytes = 300_000)

        assertTrue("body was over ceiling before", before > 300_000)
        assertTrue("body now under ceiling", r.bytesAfter <= 300_000)
        assertTrue("something was elided", r.elidedToolResultCount > 0)
        // Every tool_result id survives (pairing intact) even when content elided.
        assertEquals("all 20 tool_result ids preserved", 20, toolResultIds(r.messages).size)
        // Elided ones carry the marker; the rest are verbatim.
        assertTrue("at least one elided marker present",
            toolResultContents(r.messages).any { it.startsWith(RequestBudget.ELIDED_PREFIX) })
    }

    @Test
    fun `never elides inside the protected tail`() {
        val msgs = ArrayList<LLMMessage>()
        for (n in 1..10) {
            msgs.add(user("old $n")); msgs.add(asstToolUse("old$n")); msgs.add(userToolResult("old$n", 40_000))
        }
        // 6 fresh user-text turns, each with its own big tool_result
        for (n in 1..6) {
            msgs.add(user("fresh $n")); msgs.add(asstToolUse("fresh$n")); msgs.add(userToolResult("fresh$n", 40_000))
        }
        val r = RequestBudget.plan(msgs, protectRecentUserTextTurns = 6, maxBodyBytes = 300_000)
        // Fresh tool_results must remain full-size (never elided).
        for (n in 1..6) {
            val fresh = r.messages.flatMap { it.contentParts }
                .filterIsInstance<AgentContentPart.ToolResult>().first { it.id == "fresh$n" }
            assertEquals("fresh$n kept verbatim", 40_000, fresh.content.length)
            assertFalse(fresh.content.startsWith(RequestBudget.ELIDED_PREFIX))
        }
    }

    @Test
    fun `offload stubs and already-elided results are not touched`() {
        val stub = "${ContextOffload.OFFLOADED_PREFIX} big /var/minis/offloads/tools/x.txt"
        val msgs = ArrayList<LLMMessage>()
        msgs.add(user("old"))
        msgs.add(asstToolUse("s1"))
        msgs.add(LLMMessage(LLMMessage.Role.USER, "",
            contentParts = listOf(AgentContentPart.ToolResult("s1", "shell_execute", stub))))
        // pad body over ceiling with a genuinely large, elidable result
        msgs.add(asstToolUse("big")); msgs.add(userToolResult("big", 400_000))
        for (n in 1..6) msgs.add(user("fresh $n"))

        val r = RequestBudget.plan(msgs, protectRecentUserTextTurns = 6, maxBodyBytes = 300_000)
        val contents = toolResultContents(r.messages)
        assertTrue("offload stub preserved verbatim", contents.contains(stub))
        assertTrue("the big elidable result was elided",
            contents.any { it.startsWith(RequestBudget.ELIDED_PREFIX) })
    }

    @Test
    fun `elision placeholder points at the offload path when present`() {
        val msgs = ArrayList<LLMMessage>()
        msgs.add(user("old")); msgs.add(asstToolUse("p"))
        msgs.add(userToolResult("p", 400_000, name = "read_image", path = "/var/minis/offloads/tools/p.png"))
        for (n in 1..6) msgs.add(user("fresh $n"))
        val r = RequestBudget.plan(msgs, protectRecentUserTextTurns = 6, maxBodyBytes = 300_000)
        val elided = toolResultContents(r.messages).first { it.startsWith(RequestBudget.ELIDED_PREFIX) }
        assertTrue("names the re-fetch path", elided.contains("/var/minis/offloads/tools/p.png"))
        assertTrue("mentions read_image", elided.contains("read_image"))
    }

    @Test
    fun `empty input is safe`() {
        val r = RequestBudget.plan(emptyList(), 6)
        assertTrue(r.messages.isEmpty())
        assertEquals(0, r.elidedToolResultCount)
    }

    @Test
    fun `body that cannot shrink below ceiling degrades gracefully`() {
        // Only fresh (protected) turns carry the weight — nothing is elidable.
        val msgs = ArrayList<LLMMessage>()
        for (n in 1..6) {
            msgs.add(user("fresh $n")); msgs.add(asstToolUse("f$n")); msgs.add(userToolResult("f$n", 100_000))
        }
        val r = RequestBudget.plan(msgs, protectRecentUserTextTurns = 6, maxBodyBytes = 300_000)
        // Cannot go under ceiling without touching protected tail: it returns
        // unchanged rather than corrupting the working context.
        assertEquals(0, r.elidedToolResultCount)
        assertSame(msgs, r.messages)
    }

    @Test
    fun `stops eliding as soon as body fits`() {
        val msgs = ArrayList<LLMMessage>()
        // 3 old 200k results (600k) — eliding ONE (200k) should drop under 300k
        // together with the rest of the small body.
        for (n in 1..3) {
            msgs.add(user("q$n")); msgs.add(asstToolUse("t$n")); msgs.add(userToolResult("t$n", 200_000))
        }
        for (n in 1..6) msgs.add(user("fresh $n"))
        val r = RequestBudget.plan(msgs, protectRecentUserTextTurns = 6, maxBodyBytes = 300_000)
        assertTrue("fits after elision", r.bytesAfter <= 300_000)
        // Should not have elided all 3 when fewer suffice.
        assertTrue("elided the minimum needed (<3)", r.elidedToolResultCount < 3)
    }
}
