package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-compact-external] FULL transcript for the external-compaction export.
 *
 * Deliberately does NOT share code with the internal window collector
 * (recency tiers, body caps, dedup): the external model must receive the
 * COMPLETE session exactly where the user expects it ("ничего не забыто?").
 * One file, one shot, full fidelity: every message body, every tool_use
 * input, every tool_result body verbatim.
 *
 * Images cannot ride a text export; they render as an informative stub so
 * the external model knows an image existed (and its size) instead of
 * silently losing the turn shape.
 *
 * Unit-tested over all four AgentContentPart kinds (see
 * FullConversationTranscriptTest).
 */
object FullConversationTranscript {

    fun render(history: List<LLMMessage>): String = buildString {
        var turn = 0
        for (msg in history) {
            val role = msg.role.name.lowercase()
            val isRealUserTurn = msg.role == LLMMessage.Role.USER &&
                (msg.content.isNotBlank() ||
                    msg.contentParts.any { it !is AgentContentPart.ToolResult })
            if (isRealUserTurn) {
                turn += 1
                append("\n### Turn ").append(turn).append('\n')
            }
            if (msg.content.isNotEmpty()) {
                append(role).append(": ").append(msg.content).append('\n')
            }
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> {
                        append("  ").append(role).append(": ").append(part.text).append('\n')
                    }
                    is AgentContentPart.ToolUse -> {
                        append("  ").append(role).append(" [tool:")
                            .append(part.name).append("]: ")
                            .append(part.input.toString())
                            .append('\n')
                    }
                    is AgentContentPart.ToolResult -> {
                        append("  ").append(role)
                            .append(" [result:").append(part.name).append("]: ")
                            .append(part.content)
                            .append('\n')
                    }
                    is AgentContentPart.ImageData -> {
                        append("  ").append(role)
                            .append(" [image: ")
                            .append(part.mimeType)
                            .append(", ")
                            .append(part.data.size)
                            .append(" bytes — недоступно в текстовом экспорте]")
                            .append('\n')
                    }
                }
            }
        }
    }
}
