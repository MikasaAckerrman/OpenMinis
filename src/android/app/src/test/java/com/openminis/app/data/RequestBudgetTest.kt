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

    @Test
    fun `many mid-sized results still get the body under the ceiling`() {
        // [T-postanchor-preserve-live-context] РЕГРЕСС-ГАРАНТ. Раньше порог
        // символов был жёстким фильтром: тело из множества СРЕДНИХ результатов
        // (ни один не больше порога) давало ПУСТОЙ список кандидатов, шлюз
        // ничего не заглушал и оверсайз-запрос уходил провайдеру — ровно тот
        // отказ, ради которого шлюз и написан. Теперь порог — предпочтение.
        val msgs = ArrayList<LLMMessage>()
        for (n in 1..80) {
            msgs.add(user("q$n")); msgs.add(asstToolUse("t$n"))
            // 5000 симв. — НИЖЕ MIN_ELIDABLE_TOOL_RESULT_CHARS (8000)
            msgs.add(userToolResult("t$n", 5000))
        }
        for (n in 1..6) msgs.add(user("fresh $n"))
        val before = RequestBudget.estimateBytes(msgs)
        assertTrue("фикстура выше потолка", before > 300_000)
        val r = RequestBudget.plan(msgs, protectRecentUserTextTurns = 6, maxBodyBytes = 300_000)
        assertTrue("шлюз сработал на средних результатах", r.elidedToolResultCount > 0)
        assertTrue("тело влезло под потолок", r.bytesAfter <= 300_000)
        assertTrue("заглушено не всё", r.elidedToolResultCount < 80)
    }

    // ── [T-image-bytes-visible] history images at wire size ───────────────

    /**
     * The live failure (2026-09-09): three ~600 KB screenshots inline in the
     * protected-adjacent history produced a 1.31 MB body while estimateBytes
     * counted every image as ZERO («ImageBudget owns image bytes» — its cap
     * is 25 MB, an order above the relay's ~1 MB). The gate must SEE the
     * base64 wire cost.
     */
    @Test
    fun `estimateBytes charges inline images at base64 wire size`() {
        val png = ByteArray(600_000) { 'x'.code.toByte() }
        val withImage = LLMMessage(
            LLMMessage.Role.USER, "see this",
            contentParts = listOf(AgentContentPart.ImageData(png, "image/png")),
        )
        val bare = LLMMessage(LLMMessage.Role.USER, "see this")
        val diff = RequestBudget.estimateBytes(listOf(withImage)) -
            RequestBudget.estimateBytes(listOf(bare))
        assertEquals(
            (600_000 * 4) / 3,
            diff,
        )
    }

    @Test
    fun `toolResult imageData also charges wire bytes`() {
        val png = ByteArray(300_000) { 'y'.code.toByte() }
        val withImage = LLMMessage(
            LLMMessage.Role.USER, "",
            contentParts = listOf(
                AgentContentPart.ToolResult("i1", "read_image", "done", imageData = png),
            ),
        )
        assertTrue(
            RequestBudget.estimateBytes(listOf(withImage)) > (300_000 * 4) / 3 - 100,
        )
    }

    @Test
    fun `oversize old images are elided to stubs, fresh images are not`() {
        val bigPng = ByteArray(500_000) { 'z'.code.toByte() }
        val msgs = ArrayList<LLMMessage>()
        // OLD turn with a fat screenshot — outside the 3-turn image shield.
        msgs.add(LLMMessage(LLMMessage.Role.USER, "look", contentParts =
            listOf(AgentContentPart.ImageData(bigPng, "image/png", "/tmp/old.png"))))
        // padding tool round so the body is clearly over the ceiling
        msgs.add(user("pad")); msgs.add(asstToolUse("big")); msgs.add(userToolResult("big", 400_000))
        // 3 fresh text turns (image shield = last 3 user turns; tool shield = 6)
        for (n in 1..3) msgs.add(user("fresh $n"))
        // CURRENT turn with a screenshot — inside the shield, never elided.
        msgs.add(LLMMessage(LLMMessage.Role.USER, "and this one", contentParts =
            listOf(AgentContentPart.ImageData(bigPng, "image/png"))))

        val before = RequestBudget.estimateBytes(msgs)
        val r = RequestBudget.plan(msgs, protectRecentUserTextTurns = 6, maxBodyBytes = 300_000)
        assertTrue("fixture over ceiling", before > 300_000)
        assertTrue("an image was elided", r.elidedImageCount > 0)
        // The shielded current image + shielded pad tool_result legitimately
        // remain — the provider's compression ladder finishes those. What the
        // gate OWNS here is reclaiming the old image's ~667 KB of b64.
        assertTrue(
            "old image reclaimed (before=$before after=${r.bytesAfter})",
            before - r.bytesAfter > 600_000,
        )
        // The current-turn image (inside the image shield) is untouched.
        val currentImages = r.messages.last().contentParts
            .filterIsInstance<AgentContentPart.ImageData>()
        assertEquals("current-turn image kept verbatim", 1, currentImages.size)
        // The old image became a Text stub mentioning the re-fetch path.
        val stub = r.messages.first().contentParts
            .filterIsInstance<AgentContentPart.Text>()
            .firstOrNull { it.text.startsWith(RequestBudget.IMAGE_ELIDED_PREFIX) }
        assertTrue("old image replaced with stub", stub != null)
        assertTrue("stub names the path", stub?.text?.contains("/tmp/old.png") == true)
    }

    @Test
    fun `toolResult image is stripped without losing its text content`() {
        val png = ByteArray(500_000) { 'q'.code.toByte() }
        val msgs = ArrayList<LLMMessage>()
        msgs.add(user("old"))
        msgs.add(LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
            AgentContentPart.ToolResult("img1", "read_image", "image captured OK",
                imageData = png, imageLinuxPath = "/tmp/x.png"),
        )))
        msgs.add(asstToolUse("big")); msgs.add(userToolResult("big", 400_000))
        // 4 fresh turns: the image tool_result falls outside the 3-turn shield.
        for (n in 1..4) msgs.add(user("fresh $n"))

        val r = RequestBudget.plan(msgs, protectRecentUserTextTurns = 6, maxBodyBytes = 300_000)
        val stripped = r.messages.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>()
            .first { it.id == "img1" }
        assertTrue("text content preserved", stripped.content.contains("image captured OK"))
        assertTrue("image bytes removed", stripped.imageData == null)
    }

    @Test
    fun `image inside the freshest 3 turns is never elided even when huge`() {
        val bigPng = ByteArray(800_000) { 'w'.code.toByte() }
        val msgs = ArrayList<LLMMessage>()
        // old fat tool_result, then ENOUGH fresh turns that the 6-turn tool
        // shield boundary lands BEFORE it (fewer turns than the shield would
        // protect everything — documented semantics).
        msgs.add(user("q")); msgs.add(asstToolUse("t0")); msgs.add(userToolResult("t0", 400_000))
        for (n in 1..4) msgs.add(user("filler $n"))
        for (n in 1..2) msgs.add(user("fresh $n"))
        // current turn carries a fat image
        msgs.add(LLMMessage(LLMMessage.Role.USER, "see", contentParts =
            listOf(AgentContentPart.ImageData(bigPng, "image/png"))))

        val r = RequestBudget.plan(msgs, protectRecentUserTextTurns = 6, maxBodyBytes = 300_000)
        assertEquals("current image not elided", 0, r.elidedImageCount)
        val kept = r.messages.last().contentParts
            .filterIsInstance<AgentContentPart.ImageData>()
        assertEquals("image bytes still inline", 1, kept.size)
        assertTrue("old tool result was elided instead", r.elidedToolResultCount > 0)
    }
}
