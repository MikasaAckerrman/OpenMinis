package com.openminis.app.data

/**
 * [T-copy-whole-answer] Build the plain-text copy of a whole assistant turn.
 *
 * Why a policy and not a `joinToString` at the call site: an assistant turn is
 * not one string. It is a list of blocks — prose, thinking, tool calls — and
 * "copy the answer" has to make three decisions that are easy to get wrong and
 * invisible on review:
 *
 *  1. Which blocks belong in the copy. Prose does. Tool payloads do not: a
 *     single shell result can be tens of thousands of characters and would bury
 *     the answer the user actually wanted, and the tool capsule already has its
 *     own "copy details" action for that case.
 *  2. Whether reasoning is included. It is not, by default: the user asked for
 *     the answer, and thinking text is often longer than it.
 *  3. What separates the kept pieces. Prose blocks are consecutive paragraphs
 *     of one reply, so they join with a blank line — the same shape the
 *     renderer shows — instead of running together into one wall.
 *
 * Ordering is the block order as stored, which is the order rendered.
 */
object AssistantTurnCopy {

    /**
     * A block reduced to what this policy needs. Keeps the module free of the
     * UI's `AssistantBlock` (and therefore unit-testable without Compose).
     */
    data class Block(
        /** "text", "tool_use", "thinking", "info". */
        val kind: String,
        val content: String,
    )

    /** Kinds whose content is part of the answer. */
    private val PROSE_KINDS = setOf("text")

    /**
     * Plain text of the whole turn, or an empty string when there is nothing
     * quotable.
     *
     * [legacyContent] covers pre-migration rows that stored the whole reply in
     * `message.content` with no text-kind blocks — the same fallback
     * `buildFlatChatItems` applies when rendering, so copy and screen agree.
     * It is used ONLY when no prose block survived, never appended on top of
     * one: for a migrated row it holds the same words and would duplicate them.
     */
    fun plainText(
        blocks: List<Block>,
        legacyContent: String = "",
        includeThinking: Boolean = false,
    ): String {
        val kept = blocks.asSequence()
            .filter { it.kind in PROSE_KINDS || (includeThinking && it.kind == "thinking") }
            .map { it.content.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (kept.isEmpty()) return legacyContent.trim()
        return kept.joinToString("\n\n")
    }

    /**
     * True when a copy action is worth offering. Guards the menu item: a turn
     * that is nothing but tool calls has no answer to copy, and an enabled item
     * that silently copies "" is worse than an absent one.
     */
    fun hasCopyableText(blocks: List<Block>, legacyContent: String = ""): Boolean =
        plainText(blocks, legacyContent).isNotEmpty()
}
