package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [T-agent-pill] Контракт оформления строки субагента.
 *
 * Строка агента создаётся в `ChatViewModel.subagentBlock` с
 * `toolName = "spawn_subagent"` — это и есть [AGENT_TOOL]. Плашка агента
 * должна отличаться от всех остальных плашек тулов: свой цвет (розовый) и
 * своя иконка (дерево агентов), чтобы в логе читалось «работают N агентов»,
 * а не одна сводная плашка на пачку.
 */
class ChatToolFormattingTest {

    @Test
    fun `agent tool uses the pink accent`() {
        assertEquals(Color(0xFFFF2D55), toolAccentColor(AGENT_TOOL))
    }

    @Test
    fun `agent accent differs from the fallback accent`() {
        assertNotEquals(
            toolAccentColor("some_tool_that_does_not_exist"),
            toolAccentColor(AGENT_TOOL),
        )
    }

    @Test
    fun `agent tool uses its own icon`() {
        assertEquals("AccountTree", toolIconFor(AGENT_TOOL).name)
    }

    @Test
    fun `agent icon differs from the fallback icon`() {
        assertNotEquals(
            toolIconFor("some_tool_that_does_not_exist").name,
            toolIconFor(AGENT_TOOL).name,
        )
    }

    @Test
    fun `agent labels do not leak the raw tool name`() {
        assertEquals("agent", toolDisplayName(AGENT_TOOL))
        assertEquals("Minis is running an Agent", toolTitleLabel(AGENT_TOOL))
    }
}
