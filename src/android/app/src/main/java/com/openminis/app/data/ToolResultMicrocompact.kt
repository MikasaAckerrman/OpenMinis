package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-tool-microcompact] Wire-side compaction of OLD tool_result payloads —
 * the Cline insight: in an agent loop the context is eaten by tool OUTPUTS
 * (a shell dump or browser_extract can be 100 KB per call), not by user
 * turns. Full compaction rewrites the whole history; the cheaper first line
 * of defence is to keep every message and tool pairing INTACT (protocol
 * safe — the provider still sees tool_use id X answered by tool_result id X)
 * while truncating the BODIES of tool results that are both old and fat.
 *
 * Applied ONLY on the no-marker path (effectiveAgentHistory returning the
 * full history before any compaction): it stretches how long a session can
 * run before a real compact is needed. The protected tail and every
 * post-marker message stay verbatim.
 *
 * Pure Kotlin, no Android — unit-tested on the JVM like ProtectedTail.
 */
object ToolResultMicrocompact {

    /** Entries at the END of history that must stay verbatim. */
    const val KEEP_RECENT_ENTRIES = 8

    /** Below this size a tool result is not worth a rewrite. */
    const val MIN_TRUNCATABLE_CHARS = 1_200

    /** Head/tail kept around the elision marker. */
    const val KEEP_HEAD_CHARS = 400
    const val KEEP_TAIL_CHARS = 200

    /**
     * Truncate fat old tool_result bodies. [history] is NOT mutated — a new
     * list is returned (callers may hold the original for persistence).
     * Errors are preserved verbatim regardless of size: an error body is
     * small and is exactly the context a future turn needs to avoid
     * repeating a failed approach.
     */
    fun apply(history: List<LLMMessage>): List<LLMMessage> {
        if (history.size <= KEEP_RECENT_ENTRIES) return history
        val cutoff = history.size - KEEP_RECENT_ENTRIES
        var changed = false
        val out = history.mapIndexed { idx, msg ->
            if (idx >= cutoff) return@mapIndexed msg
            val parts = msg.contentParts ?: return@mapIndexed msg
            if (parts.none { it is AgentContentPart.ToolResult && it.content.length >= MIN_TRUNCATABLE_CHARS && !it.isError }) {
                return@mapIndexed msg
            }
            changed = true
            msg.copy(
                contentParts = parts.map { part ->
                    if (part is AgentContentPart.ToolResult &&
                        !part.isError &&
                        part.content.length >= MIN_TRUNCATABLE_CHARS
                    ) {
                        part.copy(content = elide(part.content))
                    } else {
                        part
                    }
                },
            )
        }
        return if (changed) out else history
    }

    private fun elide(content: String): String {
        val head = content.take(KEEP_HEAD_CHARS)
        val tail = content.takeLast(KEEP_TAIL_CHARS)
        return "$head\n\n[… tool output microcompacted: ${content.length} chars total; " +
            "full text persists in the session DB and can be re-read via the file bridge …]\n\n$tail"
    }
}
