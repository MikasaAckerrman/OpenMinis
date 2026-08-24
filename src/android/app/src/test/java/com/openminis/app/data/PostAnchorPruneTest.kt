package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-postanchor-payload-bloat] Pins the postAnchor prune: oversize tool_results
 * in already-summarised turns are removed to keep the request body small, while
 * the freshest `keepN` user-text turns stay verbatim. Measured live: request
 * body swung 243 KB (accepted) to 1.28 MB (rejected as `sensitive_words`) purely
 * on un-pruned postAnchor tool_results.
 */
class PostAnchorPruneTest {

    private fun user(text: String) = LLMMessage(LLMMessage.Role.USER, text)

    private fun asstToolUse(id: String) = LLMMessage(
        LLMMessage.Role.ASSISTANT,
        "",
        contentParts = listOf(AgentContentPart.ToolUse(id, "shell_execute", org.json.JSONObject())),
    )

    private fun userToolResult(id: String, chars: Int) = LLMMessage(
        LLMMessage.Role.USER,
        "",
        contentParts = listOf(AgentContentPart.ToolResult(id, "shell_execute", "x".repeat(chars))),
    )

    private fun toolResultIds(msgs: List<LLMMessage>): Set<String> =
        msgs.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>().map { it.id }.toSet()

    private fun toolUseIds(msgs: List<LLMMessage>): Set<String> =
        msgs.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolUse>().map { it.id }.toSet()

    private fun bodyLen(msgs: List<LLMMessage>): Int =
        msgs.sumOf { m ->
            m.content.length + m.contentParts.sumOf { p ->
                when (p) {
                    is AgentContentPart.Text -> p.text.length
                    is AgentContentPart.ToolResult -> p.content.length
                    else -> 0
                }
            }
        }

    @Test
    fun `drops old oversize tool_results but keeps the fresh tail verbatim`() {
        val msgs = ArrayList<LLMMessage>()
        for (n in 1..10) {
            msgs.add(user("old question $n"))
            msgs.add(asstToolUse("old$n"))
            msgs.add(userToolResult("old$n", 5000))
        }
        for (n in 1..6) {
            msgs.add(user("fresh question $n"))
            msgs.add(asstToolUse("fresh$n"))
            msgs.add(userToolResult("fresh$n", 5000))
        }
        val before = bodyLen(msgs)
        val result = PostAnchorPrune.prune(msgs, protectRecentUserTextTurns = 6,
            maxToolResultChars = 1000, emergencyThresholdBytes = 0)
        val after = bodyLen(result.messages)

        assertEquals("all 10 old oversize tool_results dropped", 10, result.droppedToolResultCount)
        assertTrue("all 6 fresh tool_results survive", (1..6).all { toolResultIds(result.messages).contains("fresh$it") })
        assertTrue("no old tool_result survives", (1..10).none { toolResultIds(result.messages).contains("old$it") })
        assertTrue("no dangling old tool_use", (1..10).none { toolUseIds(result.messages).contains("old$it") })
        assertTrue("fresh tool_use all present", (1..6).all { toolUseIds(result.messages).contains("fresh$it") })
        assertTrue("body shrank by the dropped bytes (>40k)", before - after > 40_000)
    }

    @Test
    fun `leaves small tool_results untouched`() {
        val msgs = ArrayList<LLMMessage>()
        for (n in 1..10) {
            msgs.add(user("q$n")); msgs.add(asstToolUse("s$n")); msgs.add(userToolResult("s$n", 200))
        }
        for (n in 1..6) msgs.add(user("fresh $n"))
        val result = PostAnchorPrune.prune(msgs, 6, maxToolResultChars = 1000, emergencyThresholdBytes = 0)
        assertEquals(0, result.droppedToolResultCount)
        assertSame("identity-returned when nothing dropped", msgs, result.messages)
    }

    @Test
    fun `protects everything when fewer than keepN user turns exist`() {
        val msgs = ArrayList<LLMMessage>()
        msgs.add(user("q1")); msgs.add(asstToolUse("a1")); msgs.add(userToolResult("a1", 5000))
        msgs.add(user("q2")); msgs.add(asstToolUse("a2")); msgs.add(userToolResult("a2", 5000))
        msgs.add(user("q3"))
        val result = PostAnchorPrune.prune(msgs, 6, maxToolResultChars = 1000, emergencyThresholdBytes = 0)
        assertEquals(0, result.droppedToolResultCount)
        assertSame(msgs, result.messages)
    }

    @Test
    fun `empty input is safe`() {
        val result = PostAnchorPrune.prune(emptyList(), 6, maxToolResultChars = 1000, emergencyThresholdBytes = 0)
        assertTrue(result.messages.isEmpty())
        assertEquals(0, result.droppedToolResultCount)
    }

    @Test
    fun `keepN of zero makes the whole slice prunable`() {
        val msgs = ArrayList<LLMMessage>()
        for (n in 1..5) {
            msgs.add(user("q$n")); msgs.add(asstToolUse("a$n")); msgs.add(userToolResult("a$n", 5000))
        }
        val result = PostAnchorPrune.prune(msgs, 0, maxToolResultChars = 1000, emergencyThresholdBytes = 0)
        assertEquals(5, result.droppedToolResultCount)
    }

    @Test
    fun `removes messages emptied by pruning rather than leaving empty shells`() {
        val msgs = ArrayList<LLMMessage>()
        msgs.add(user("old"))
        msgs.add(asstToolUse("x1"))          // only a tool_use -> emptied -> removed
        msgs.add(userToolResult("x1", 5000)) // only a tool_result -> emptied -> removed
        for (n in 1..6) msgs.add(user("fresh $n"))
        val result = PostAnchorPrune.prune(msgs, 6, maxToolResultChars = 1000, emergencyThresholdBytes = 0)
        assertEquals(1, result.droppedToolResultCount)
        assertFalse(
            "no empty-shell assistant message remains",
            result.messages.any {
                it.role == LLMMessage.Role.ASSISTANT && it.contentParts.isEmpty() && it.content.isBlank()
            },
        )
        assertFalse("dropped tool_use is gone", toolUseIds(result.messages).contains("x1"))
    }

    @Test
    fun `keeps a mixed message but strips only the dropped tool parts`() {
        // Assistant message carrying BOTH text and a doomed tool_use: the text
        // must survive, the tool_use must go.
        val mixed = LLMMessage(
            LLMMessage.Role.ASSISTANT,
            "",
            contentParts = listOf(
                AgentContentPart.Text("here is my reasoning"),
                AgentContentPart.ToolUse("big", "shell_execute", org.json.JSONObject()),
            ),
        )
        val msgs = ArrayList<LLMMessage>()
        msgs.add(user("old"))
        msgs.add(mixed)
        msgs.add(userToolResult("big", 5000))
        for (n in 1..6) msgs.add(user("fresh $n"))
        val result = PostAnchorPrune.prune(msgs, 6, maxToolResultChars = 1000, emergencyThresholdBytes = 0)
        assertEquals(1, result.droppedToolResultCount)
        val texts = result.messages.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.Text>().map { it.text }
        assertTrue("reasoning text preserved", texts.contains("here is my reasoning"))
        assertFalse("tool_use stripped", toolUseIds(result.messages).contains("big"))
    }

    // ─── [T-postanchor-preserve-live-context] ───────────────────────────────
    // postAnchor — ЖИВАЯ часть беседы, её не покрывает никакой summary.
    // Поэтому обрезка здесь допустима ТОЛЬКО при реальном раздутии.

    @Test
    fun `normal-sized live slice passes through untouched with defaults`() {
        // 30 витков с крупными (5000 симв.) результатами — типичная длинная
        // сессия с инструментами. Суммарно ~450 KB: НИЖЕ аварийного порога,
        // значит уходит модели дословно.
        val msgs = ArrayList<LLMMessage>()
        for (n in 1..30) {
            msgs.add(user("question $n"))
            msgs.add(asstToolUse("t$n"))
            msgs.add(userToolResult("t$n", 5000))
        }
        val result = PostAnchorPrune.prune(msgs, protectRecentUserTextTurns = 24)
        assertEquals("ни один результат не удалён", 0, result.droppedToolResultCount)
        assertSame("возвращён тот же список без копирования", msgs, result.messages)
    }

    @Test
    fun `valve opens only past the emergency threshold`() {
        // 200 витков по 5000 симв. ≈ 1 MB — выше порога, клапан открывается.
        val msgs = ArrayList<LLMMessage>()
        for (n in 1..200) {
            msgs.add(user("question $n"))
            msgs.add(asstToolUse("t$n"))
            msgs.add(userToolResult("t$n", 5000))
        }
        assertTrue("фикстура действительно больше порога",
            RequestBudget.estimateBytes(msgs) > PostAnchorPrune.EMERGENCY_THRESHOLD_BYTES)
        val result = PostAnchorPrune.prune(msgs, protectRecentUserTextTurns = 24)
        assertTrue("при раздутии обрезка сработала", result.droppedToolResultCount > 0)
        assertTrue("тело приведено под порог",
            RequestBudget.estimateBytes(result.messages) <= PostAnchorPrune.EMERGENCY_THRESHOLD_BYTES)
        assertTrue("удалено не больше необходимого", result.droppedToolResultCount < 176)
        // Свежий хвост цел: последние 24 витка неприкосновенны.
        val ids = toolResultIds(result.messages)
        assertTrue("последние 24 результата сохранены",
            (177..200).all { ids.contains("t$it") })
    }

    @Test
    fun `ordinary output is touched only as far as needed to fit`() {
        // Результаты по 3000 симв. — под предпочтительным порогом 8000. Они
        // трогаются ТОЛЬКО потому, что иначе срез не влезает, и ровно столько,
        // сколько нужно: иначе вернулся бы отказ провайдера по размеру.
        val msgs = ArrayList<LLMMessage>()
        for (n in 1..300) {
            msgs.add(user("question $n"))
            msgs.add(asstToolUse("t$n"))
            msgs.add(userToolResult("t$n", 3000))
        }
        assertTrue("клапан открыт",
            RequestBudget.estimateBytes(msgs) > PostAnchorPrune.EMERGENCY_THRESHOLD_BYTES)
        val result = PostAnchorPrune.prune(msgs, protectRecentUserTextTurns = 24)
        assertTrue("удалено ненулевое, но минимальное количество",
            result.droppedToolResultCount > 0)
        assertTrue("срез влез под порог",
            RequestBudget.estimateBytes(result.messages) <= PostAnchorPrune.EMERGENCY_THRESHOLD_BYTES)
        val ids = toolResultIds(result.messages)
        assertTrue("свежие 24 витка целы", (277..300).all { ids.contains("t$it") })
    }

    @Test
    fun `many mid-sized results still get the body under the ceiling`() {
        // ДЫРА, найденная харнессом до отправки: 200 x 5 KB = 1 MB, но ни один
        // результат не превышает предпочтительный порог 8000. Плоский отрез по
        // порогу пропустил бы это, и оверсайз-тело вернулось бы.
        val msgs = ArrayList<LLMMessage>()
        for (n in 1..200) {
            msgs.add(user("question $n"))
            msgs.add(asstToolUse("t$n"))
            msgs.add(userToolResult("t$n", 5000))
        }
        val result = PostAnchorPrune.prune(msgs, protectRecentUserTextTurns = 24)
        assertTrue("тело приведено под порог",
            RequestBudget.estimateBytes(result.messages) <= PostAnchorPrune.EMERGENCY_THRESHOLD_BYTES)
        assertTrue("удалено не всё", result.droppedToolResultCount < 176)
        val ids = toolResultIds(result.messages)
        assertTrue("свежий хвост цел", (177..200).all { ids.contains("t$it") })
    }

    @Test
    fun `constants stay consistent with the provider byte gate`() {
        // Клапан обязан открываться РАНЬШЕ, чем начинает резать байтовый шлюз,
        // и оба — ниже единственного измеренного отказа (1.28 MB).
        assertTrue("порог клапана ниже потолка тела",
            PostAnchorPrune.EMERGENCY_THRESHOLD_BYTES < RequestBudget.DEFAULT_MAX_BODY_BYTES)
        assertTrue("потолок тела ниже измеренного отказа 1.28 MB",
            RequestBudget.DEFAULT_MAX_BODY_BYTES < 1_280_000)
        assertEquals("пороги символов согласованы",
            PostAnchorPrune.DEFAULT_MAX_TOOL_RESULT_CHARS,
            RequestBudget.MIN_ELIDABLE_TOOL_RESULT_CHARS)
    }
}
