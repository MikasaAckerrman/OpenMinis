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
        // [T-tool-duration-persist] Execution duration, measured
        // Started→Finished. The toolResult row JSON carries it so reloaded
        // sessions rebuild the "N с" badge — previously the duration lived
        // only in the live reducer memory and every DB-restored tool card
        // showed 0s.
        val durationMs: Long = 0L,
    ) : Effect

    val text = StringBuilder()
    val thinking = StringBuilder()

    private val blocks = mutableListOf<AssistantBlock>()
    private var textBlockIdx = -1
    // [T-round-text-order] Current text SEGMENT's accumulator — reset when a
    // segment closes (ToolUseStarted / ToolCallFinished). `text` above stays
    // the whole-turn mirror for message.content; the block content is the
    // segment's own text so rounds never merge into one blob.
    private val segText = StringBuilder()

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
                // [T-round-text-order] `text` accumulates the WHOLE turn
                // (message.content mirror); `segText` only the current
                // segment — the block content. A segment ends the moment a
                // tool_use is emitted (ToolUseStarted) or a tool of the
                // round finishes (ToolCallFinished: the response is over,
                // so no in-flight text can straddle it).
                text.append(event.text)
                segText.append(event.text)
                if (textBlockIdx < 0) {
                    val block = AssistantBlock(
                        id = "text_${turn}_${blocks.size}",
                        kind = "text",
                        content = segText.toString(),
                    )
                    // [T-round-text-order] POSITION IS THE FIX (07.10, the
                    // "Тулы всегда снизу, текст сверху" report): the old rule
                    // inserted EVERY new text block before the FIRST tool of
                    // the whole turn and never closed textBlockIdx — a
                    // multi-round turn collapsed into [all text merged at
                    // top][all tools below], destroying the narrative
                    // (round-2's answer rendered above the round-1 tools it
                    // followed). The honest rule is state-driven:
                    //  - tools still PENDING/RUNNING ⇒ this is trailing
                    //    content of the SAME response (qwen-style
                    //    content-after-tool_calls chunking) — the canonical
                    //    {content, tool_calls} wire shape puts it before
                    //    those tools: insert before the first unfinished one;
                    //  - all tools finished ⇒ a round boundary passed — the
                    //    text belongs to the NEXT round: append at the END,
                    //    exactly where it was emitted (between the previous
                    //    round's tools and whatever follows).
                    var firstUnfinishedTool = -1
                    for (i in blocks.indices) {
                        val b = blocks[i]
                        if (b.kind == "tool_use" &&
                            b.toolStatus != ToolBlockStatus.SUCCESS &&
                            b.toolStatus != ToolBlockStatus.FAILED &&
                            b.toolStatus != ToolBlockStatus.CANCELLED
                        ) {
                            firstUnfinishedTool = i
                            break
                        }
                    }
                    if (firstUnfinishedTool >= 0) {
                        blocks.add(firstUnfinishedTool, block)
                        textBlockIdx = firstUnfinishedTool
                    } else {
                        blocks.add(block)
                        textBlockIdx = blocks.lastIndex
                    }
                } else {
                    blocks[textBlockIdx] = blocks[textBlockIdx].copy(content = segText.toString())
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
                // [T-round-text-order] A tool_use emission closes the open
                // text segment (the response's content precedes its
                // tool_calls on the wire; any text that follows opens a NEW
                // segment).
                textBlockIdx = -1
                segText.setLength(0)
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
                val start = if (idx >= 0) blocks[idx].startTimeMs else 0L
                // [T-tool-duration-persist] Measured Started→Finished;
                // flows into the block, the PersistToolResult effect → the
                // row JSON → the reloaded tool card's "N с" badge.
                val durationMs = if (start > 0) System.currentTimeMillis() - start else 0L
                if (idx >= 0) {
                    blocks[idx] = blocks[idx].copy(
                        toolStatus = if (event.success) ToolBlockStatus.SUCCESS else ToolBlockStatus.FAILED,
                        content = event.summary,
                        durationMs = durationMs,
                    )
                }
                val persist = PersistToolResult(
                    callId = event.callId,
                    toolName = event.toolName,
                    content = event.summary,
                    isError = !event.success,
                    durationMs = durationMs,
                )
                // [T-round-text-order] A finished tool ⇒ the response that
                // produced it is over (tools only execute between
                // responses) ⇒ any text that arrives next belongs to the
                // NEXT round. Close the open text segment so the next
                // TextDelta opens a fresh block at its natural position
                // instead of merging into the previous round's text.
                textBlockIdx = -1
                segText.setLength(0)
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
