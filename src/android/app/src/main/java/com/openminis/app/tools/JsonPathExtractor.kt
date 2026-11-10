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

        // [B47-shell-parallel] A shell declares its own text's file
        // surface instead of the old undeclared GLOBAL lock — see
        // [shellResourceKeys]. Missing/blank command: empty set (the call
        // is schedulable and touches nothing the platform can see).
        toolName == "shell_execute" ->
            args.optString("command", "").takeIf { it.isNotBlank() }
                ?.let { shellResourceKeys(it) }
                ?: emptySet()

        else -> null
    }
}.getOrNull() ?: null

// ── [B47-shell-parallel] The shell command's file surface ───────────────

/** Absolute paths (2+ segments) and relative paths with a slash — the
 *  same shape TaskConflictDetector walks for task text, verbatim. */
private val SHELL_PATH_REGEX = Regex(
    """(?:/[\p{L}\p{N}_@.+-]+){2,}|[\p{L}\p{N}_@.+-]+(?:/[\p{L}\p{N}_@.+-]+)+""",
)

/** [B45-bare-files] Bare filenames with a whitelist extension ("a.txt",
 *  "test.py") — prose stays out the same way the task-text extractor
 *  keeps it out. */
private val SHELL_BARE_FILE_REGEX = Regex(
    """[\p{L}\p{N}_@+-]+\.(?:kt|java|py|txt|md|rs|toml|json|ya?ml|xml|html?|css|js|jsx|ts|tsx|sh|kts|gradle|properties|csv|tsv|log|ini|cfg|conf|sql|c|h|cpp|hpp|go|rb|php|swift|m|svg|png|jpe?g|gif|webp|pdf|zip|tar|gz|session)\b""",
    RegexOption.IGNORE_CASE,
)

/** `> target` / `>> target` with a word boundary BEFORE the arrow, so
 *  `2>/dev/null` (fd redirect, no leading space) is not captured. */
private val SHELL_REDIRECT_REGEX = Regex("""\s(>>?)\s*([^\s;|&"']+)""")

/** Roots whose mention is not a file surface (prose, system pseudo-dirs). */
private val SHELL_IGNORED_EXACT = setOf(
    "/var/minis", "/var/minis/workspace", "/var/minis/shared",
    "/var/minis/attachments", "/var/minis/offloads", "/var/minis/memory",
    "/var/minis/skills", "/tmp", "/data/data", "/var", "/etc", "/usr",
    "/bin", "/system", "/proc", "/sys", "/dev", ".",
)

private val SHELL_EXT_REGEX = Regex("""\.[A-Za-z0-9]{1,6}$""")

/**
 * [B47-shell-parallel] The resource keys ONE shell command touches, best
 * effort: redirect targets (its WRITE surface), absolute/relative paths,
 * bare whitelisted filenames. The old behavior left shell_execute
 * UNDECLARED, and the scheduler turned that into a GLOBAL barrier — every
 * shell serialized against EVERYTHING (the device report: "one tool
 * works, the others never even start"). Now independent commands run
 * parallel under the CPU gate while a shell and a file tool naming the
 * SAME extracted path still serialize (the shell is a WRITE on its
 * keys). The residual risk — a path too clever for the regexes (quoting,
 * variables) escapes the conflict net — is the Claude Code bash trade:
 * parallel-independent-shell semantics over total hermetic
 * serialization, per the reviewer's directive.
 */
internal fun shellResourceKeys(command: String): Set<String> {
    val keys = LinkedHashSet<String>()
    SHELL_REDIRECT_REGEX.findAll(command).forEach { m ->
        val target = m.groupValues[2].trim('"', '\'')
        val ignored = target.startsWith("/dev/") || target.all { it.isDigit() }
        if (target.isNotEmpty() && !ignored) {
            keys.add("fs:${normalizeResourcePath(target)}")
        }
    }
    SHELL_PATH_REGEX.findAll(command).forEach { m ->
        val token = m.value.trim('.', ',', ';', ')', ']', '"', '\'')
        val hasExt = SHELL_EXT_REGEX.containsMatchIn(token.substringAfterLast('/'))
        val generic = token in SHELL_IGNORED_EXACT
        if ((hasExt || token.count { it == '/' } >= 2) && !generic && !token.startsWith("/dev/")) {
            keys.add("fs:${normalizeResourcePath(token)}")
        }
    }
    SHELL_BARE_FILE_REGEX.findAll(command).forEach { m ->
        val bare = m.value
        // A bare name already covered as the last segment of an extracted
        // path adds no surface — "a.txt" inside
        // /var/minis/workspace/a.txt is the same file.
        val covered = keys.any { it.endsWith("/$bare") }
        if (!covered) keys.add("fs:${normalizeResourcePath(bare)}")
    }
    return keys
}

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
