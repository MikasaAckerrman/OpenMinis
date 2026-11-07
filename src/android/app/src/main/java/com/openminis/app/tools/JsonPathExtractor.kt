package com.openminis.app.tools

import org.json.JSONObject

/**
 * [T-m7-path-extractor] The platform's real path extractor for the
 * engine's ToolBatchPlanner: the model's args object carries the target
 * under "path". Lives on the platform side (org.json is an Android SDK
 * dependency — the engine package stays pure Kotlin, the planner takes
 * the extractor as an injected lambda).
 */
internal fun jsonObjectPathExtractor(toolName: String, argsJson: String): String =
    runCatching {
        JSONObject(argsJson).optString("path", "").trim()
    }.getOrNull() ?: ""

/**
 * The textual path canonicalization of [PARALLEL_TOOL_ENGINE_SPEC §3.2]:
 * `.` segments drop, `..` pops (and cannot pop past the root of an
 * absolute path), duplicate separators collapse. Symlinks and the
 * session cwd are NOT resolved here — the extractor is a pure
 * (toolName, argsJson) function and the session cwd never reaches it;
 * textual canonicalization already unifies the variants the model
 * actually emits (`./src/a.rs` vs `src/a.rs`, `a/b/../c` vs `a/c`).
 * Cwd-aware absolute canonicalization is a follow-up behind the
 * injected-lambda seam.
 */
internal fun normalizeResourcePath(p: String): String {
    val absolute = p.startsWith("/")
    val parts = ArrayList<String>()
    for (seg in p.split('/')) {
        when (seg) {
            "", "." -> {}
            ".." -> when {
                parts.isNotEmpty() && parts.last() != ".." -> parts.removeAt(parts.size - 1)
                !absolute -> parts.add("..")
                // An absolute path cannot go above its root.
                else -> {}
            }
            else -> parts.add(seg)
        }
    }
    val joined = parts.joinToString("/")
    return when {
        absolute -> "/$joined"
        joined.isEmpty() -> "."
        else -> joined
    }
}

/**
 * [PARALLEL_TOOL_ENGINE_SPEC §3.1] The platform's resource-key extractor
 * for the engine's ToolScheduler — the taxonomy that lifts the false
 * GLOBAL barriers: grep/glob declared tree reads, web_search a network
 * read, the memory tier a shared `mem:store`, and every Telegram MCP
 * tool its chat-scoped MCP key. Declared resources:
 *  - fs file tools -> {"fs:<normalized path>"} (missing/blank path stays
 *    UNDECLARED — the conservative global lock, never an empty key that
 *    would silently ride a parallel wave);
 *  - grep/glob -> {"fs_tree:<normalized dir>"} (the executor's own
 *    default "/var/minis" is mirrored here — a declared default is more
 *    precise than GLOBAL, and a whole-tree read genuinely races any
 *    write under that tree);
 *  - web_search -> {"net:search"} (one shared key: searches are pure
 *    reads, any number of them runs in parallel);
 *  - webfetch -> {"net:<url>"} ([P1-io-concurrency] idempotent GET;
 *    missing url stays UNDECLARED);
 *  - memory reads -> {"mem:store"} READ, memory/todo writes -> the same
 *    key WRITE (the old "memory:journal" single-writer semantics are
 *    preserved by the write/write rule, and reads now order against
 *    writes instead of taking the GLOBAL lock);
 *  - tg_* (the Telegram vault's MCP surface) -> {"mcp:tg:chat_<id>"} per
 *    chat (chat_id, or every id of chat_ids), the chat-less tools ->
 *    {"mcp:tg:global_read"}. All 21 are read-only today (the vault's
 *    iron rule — no account-mutating tool exists); a future tg write
 *    must keep the same key and flip its effect.
 *  - everything else -> null (GLOBAL lock: serialize alone).
 * Returns a SET because tools may span multiple resources; the
 * scheduler treats any INTERSECTION — exact or hierarchical
 * (fs_tree vs fs, ToolScheduler.keysOverlap) — as a conflict.
 */
internal fun jsonObjectResourceKeys(toolName: String, argsJson: String): Set<String>? = runCatching {
    val args = JSONObject(argsJson)
    when {
        toolName == "file_read" || toolName == "file_write" ||
            toolName == "file_edit" || toolName == "read_image" ->
            args.optString("path", "").trim()
                .takeIf { it.isNotEmpty() }
                ?.let { setOf("fs:${normalizeResourcePath(it)}") }

        toolName == "grep" || toolName == "glob" ->
            setOf("fs_tree:${normalizeResourcePath(args.optString("path", "").trim().ifEmpty { "/var/minis" })}")

        toolName == "web_search" -> setOf("net:search")

        toolName == "webfetch" ->
            args.optString("url", "").trim()
                .takeIf { it.isNotEmpty() }
                ?.let { setOf("net:$it") }

        toolName in MEMORY_READ_TOOLS || toolName in MEMORY_WRITE_TOOLS -> setOf("mem:store")

        // The Telegram vault surface (MCP over 127.0.0.1:7824).
        toolName.startsWith("tg_") -> tgResourceKeys(args)

        else -> null
    }
}.getOrNull() ?: null

/** Memory-tier reads (spec §3.1): one shared store key, READ effect. */
internal val MEMORY_READ_TOOLS: Set<String> = setOf(
    "memory_get", "supermemory_search", "memory_blocks_view", "todo_read",
)

/** Memory-tier writes: the same store key, WRITE effect (todo_write
 *  moved out of META by the spec — it is a resource write, not turn
 *  state, and two of them must still serialize on the key). */
internal val MEMORY_WRITE_TOOLS: Set<String> = setOf(
    "memory_write", "memory_blocks_edit", "todo_write",
)

/**
 * The chat scope of one tg_* call: one key per chat_id (or per element
 * of chat_ids — tg_multi_search / tg_read_parallel), the chat-less
 * surface tools collapse to the shared global-read key. A call without
 * ANY scope is still declared (global read) — unlike a file tool with
 * a missing path, a tg call without chat_id degrades to the global
 * dialog surface by CONTRACT, not by accident.
 */
private fun tgResourceKeys(args: JSONObject): Set<String> {
    val chats = LinkedHashSet<String>()
    args.optLong("chat_id", Long.MIN_VALUE)
        .takeIf { it != Long.MIN_VALUE }
        ?.let { chats.add("mcp:tg:chat_$it") }
    args.optJSONArray("chat_ids")?.let { arr ->
        for (i in 0 until arr.length()) {
            arr.optLong(i, Long.MIN_VALUE)
                .takeIf { it != Long.MIN_VALUE }
                ?.let { chats.add("mcp:tg:chat_$it") }
        }
    }
    return if (chats.isEmpty()) setOf("mcp:tg:global_read") else chats
}
