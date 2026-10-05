package com.openminis.app.ui.chat

import com.openminis.app.engine.AgentEvent

/**
 * [T-m11-reducer] AgentEvent → chat turn UI state (blocks + text), the
 * renderer the engine swap drives. Faithful to the production lifecycle
 * the ViewModel loop implements today:
 *
 *  - thinking block: born on the first ThinkingDelta (id `thinking_$turn`,
 *    kind "thinking"), content accumulates verbatim, sealed SUCCESS when
 *    answer text starts flowing (the collapsed "Thinking" card);
 *  - tool blocks: born on ToolUseStarted (PENDING, args stream in via
 *    ToolInputDelta), RUNNING on ToolCallStarted, SUCCESS/FAILED on
 *    ToolCallFinished with the one-line summary as content;
 *  - text: a monolithic "text_$turn" block — when text arrives after tool
 *    blocks (content-after-tool_calls chunking) it is inserted BEFORE the
 *    first tool block of the turn, matching the canonical
 *    {content, tool_calls} wire shape the persistence path expects.
 *
 * Deliberately NOT here (swap-time wiring, the ViewModel owns them):
 * stream throttling (T94/T256 tiered gates), crash-journal heartbeats
 * (StreamDurability), DB persistence. The reducer is pure state — the
 * consumer emits [UiDirty] effects to the main thread and drains
 * [PersistToolResult] effects into the repository.
 */
class ChatTurnReducer(
    private val assistantId: String,
    private val turn: Int,
) {

    sealed interface Effect
    data class UiDirty(
        val assistantId: String,
        val text: String,
        val blocks: List<AssistantBlock>,
    ) : Effect
    data class PersistToolResult(
        val callId: String,
        val toolName: String,
        val content: String,
        val isError: Boolean,
    ) : Effect

    val text = StringBuilder()
    val thinking = StringBuilder()

    private val blocks = mutableListOf<AssistantBlock>()
    private var textBlockIdx = -1

    fun currentBlocks(): List<AssistantBlock> = blocks.toList()

    fun reduce(event: AgentEvent): List<Effect> {
        when (event) {
            is AgentEvent.ThinkingDelta -> {
                thinking.append(event.text)
                val idx = blocks.indexOfFirst { it.kind == "thinking" }
                if (idx < 0) {
                    blocks.add(
                        AssistantBlock(
                            id = "thinking_$turn",
                            kind = "thinking",
                            content = thinking.toString(),
                            toolTitle = "Thinking",
                        ),
                    )
                } else {
                    blocks[idx] = blocks[idx].copy(content = thinking.toString())
                }
                return listOf(ui())
            }

            is AgentEvent.TextDelta -> {
                // seal the thinking card once the answer starts
                val thinkIdx = blocks.indexOfFirst { it.kind == "thinking" }
                if (thinkIdx >= 0 && blocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                    blocks[thinkIdx] = blocks[thinkIdx].copy(toolStatus = ToolBlockStatus.SUCCESS)
                }
                text.append(event.text)
                if (textBlockIdx < 0) {
                    val block = AssistantBlock(
                        id = "text_${turn}_${blocks.size}",
                        kind = "text",
                        content = text.toString(),
                    )
                    // text after tool calls renders BEFORE them (wire shape)
                    val firstTool = blocks.indexOfFirst { it.kind == "tool_use" }
                    if (firstTool >= 0) {
                        blocks.add(firstTool, block)
                        textBlockIdx = firstTool
                    } else {
                        blocks.add(block)
                        textBlockIdx = blocks.lastIndex
                    }
                } else {
                    blocks[textBlockIdx] = blocks[textBlockIdx].copy(content = text.toString())
                }
                return listOf(ui())
            }

            is AgentEvent.ToolUseStarted -> {
                // Oracle-parity: production seals the thinking card when a
                // tool use starts, not only when answer text flows — the
                // DeepSeek pattern (reasoning -> tool_calls, no text) would
                // otherwise leave the thinking card open forever.
                val thinkIdx = blocks.indexOfFirst { it.kind == "thinking" }
                if (thinkIdx >= 0 && blocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                    blocks[thinkIdx] = blocks[thinkIdx].copy(toolStatus = ToolBlockStatus.SUCCESS)
                }
                val idx = blocks.indexOfFirst { it.kind == "tool_use" && it.id == event.callId }
                if (idx < 0) {
                    blocks.add(
                        AssistantBlock(
                            id = event.callId,
                            kind = "tool_use",
                            toolName = event.toolName,
                            toolTitle = event.toolName,
                            toolStatus = ToolBlockStatus.PENDING,
                        ),
                    )
                }
                return listOf(ui())
            }

            is AgentEvent.ToolInputDelta -> {
                val idx = blocks.indexOfFirst { it.kind == "tool_use" && it.id == event.callId }
                if (idx >= 0) {
                    blocks[idx] = blocks[idx].copy(
                        toolArgs = blocks[idx].toolArgs + event.fragment,
                    )
                }
                return listOf(ui())
            }

            is AgentEvent.ToolCallStarted -> {
                val idx = blocks.indexOfFirst { it.kind == "tool_use" && it.id == event.callId }
                if (idx >= 0) {
                    blocks[idx] = blocks[idx].copy(
                        toolStatus = ToolBlockStatus.RUNNING,
                        toolTitle = event.title,
                        startTimeMs = System.currentTimeMillis(),
                    )
                } else {
                    blocks.add(
                        AssistantBlock(
                            id = event.callId,
                            kind = "tool_use",
                            toolName = event.toolName,
                            toolTitle = event.title,
                            toolStatus = ToolBlockStatus.RUNNING,
                            startTimeMs = System.currentTimeMillis(),
                        ),
                    )
                }
                return listOf(ui())
            }

            is AgentEvent.ToolCallFinished -> {
                val idx = blocks.indexOfFirst { it.kind == "tool_use" && it.id == event.callId }
                if (idx >= 0) {
                    val start = blocks[idx].startTimeMs
                    blocks[idx] = blocks[idx].copy(
                        toolStatus = if (event.success) ToolBlockStatus.SUCCESS else ToolBlockStatus.FAILED,
                        content = event.summary,
                        durationMs = if (start > 0) System.currentTimeMillis() - start else 0L,
                    )
                }
                val persist = PersistToolResult(
                    callId = event.callId,
                    toolName = event.toolName,
                    content = event.summary,
                    isError = !event.success,
                )
                return listOf(ui(), persist)
            }

            // the turn's terminal events carry no additional block state
            is AgentEvent.PlanProposed, is AgentEvent.TurnFinished,
            is AgentEvent.Error -> {
                // terminal: the caller drains pending UI/persist effects
                return emptyList()
            }
        }
    }

    private fun ui() = UiDirty(assistantId, text.toString(), blocks.toList())
}
