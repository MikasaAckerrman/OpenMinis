package com.openminis.app.tools

/**
 * [T-agent-worker-prompt] Minimal system prompt for a multi-agent worker.
 *
 * The problem it fixes: a worker session went through the ordinary chat path, so
 * it received the FULL general-assistant prompt — 22 000 characters (~5 500
 * tokens) describing android-* CLIs, minis-config, browser_use, memory, skills,
 * MCP, the terminal — and then the role contract was appended at the very end.
 * Measured on PROBE-10: 21 mentions of `android-`, 14 of `minis-config`, none of
 * which the worker can even call: its tool SCHEMA is filtered by the node's
 * allowlist, but the prompt TEXT was not.
 *
 * Two costs, both real:
 *  - money: ~5 500 wasted input tokens on EVERY call of EVERY role, so the price
 *    of adding a role was mostly balast, not work;
 *  - quality: the role contract arrived as a footnote under a long
 *    "you are a general assistant" framing it has to argue against.
 *
 * So a worker gets its own prompt: identity, its role, ONLY its tools, the
 * sandbox rules it actually needs, and the handoff contract. Everything else is
 * deliberately absent — a worker that cannot call `android-alarm` has no use for
 * its documentation.
 *
 * Kept as a pure builder (no Context, no repositories) so the exact bytes sent
 * to the model are unit-testable, which is the only way to keep a size claim
 * honest as the general prompt grows.
 */
object AgentWorkerPrompt {

    /**
     * Per-tool guidance, keyed by the canonical tool name used in node
     * allowlists. Only the entries for tools the node actually has are emitted.
     *
     * These are the traps a worker really hits in this sandbox: BusyBox ash is
     * not bash, and the shell tool is where a wrong assumption costs a whole
     * turn. Everything not load-bearing for a worker is left out on purpose.
     */
    private val TOOL_NOTES: Map<String, String> = mapOf(
        "shell_execute" to
            "- shell_execute: run a command in an isolated Alpine Linux process (BusyBox ash, " +
            "NOT bash). No `**` globstar, no brace expansion, no arrays. Use `find` for " +
            "recursive search. Heredocs mis-parse quotes/braces — write a file first, then run " +
            "it. Each call is a fresh process: no shared cwd, no shared shell state.",
        "file_read" to
            "- file_read: read a file. Prefer it over `cat` — no shell overhead, and it reports " +
            "metadata.",
        "file_write" to
            "- file_write: create or overwrite a file. Prefer it over `echo`/`printf` " +
            "redirection: atomic, and it does not mangle quoting.",
        "file_edit" to
            "- file_edit: exact string replacement in an existing file. ALWAYS file_read first; " +
            "old_string must match exactly once, whitespace included.",
        "read_image" to
            "- read_image: read an image file for visual inspection.",
        "browser_use" to
            "- browser_use: drive a browser (navigate, screenshot, get_text, …).",
        "memory_get" to
            "- memory_get: recall notes from earlier sessions by keyword.",
        "memory_write" to
            "- memory_write: save a note for later sessions.",
    )

    /**
     * Build the worker prompt.
     *
     * @param assistantName SOUL.md name, so the worker matches the app's identity.
     * @param roleContract the node's role + scope contract + handoff format,
     *   produced by the graph runner. Passed in rather than built here: the
     *   runner owns what a role means, this owns what a worker needs to survive
     *   in the sandbox.
     * @param allowedTools canonical tool names from the node's allowlist. Empty
     *   means unrestricted, in which case no per-tool section is emitted at all
     *   rather than dumping every tool's documentation — an unrestricted worker
     *   is a configuration smell, not a reason to pay 5 500 tokens.
     * @param workspaceDir the run's shared artifact directory, or null.
     */
    fun build(
        assistantName: String,
        roleContract: String,
        allowedTools: List<String>,
        workspaceDir: String? = null,
    ): String = buildString {
        append("You are ").append(assistantName.ifBlank { "Minis" })
        append(", working as ONE agent inside a multi-agent run on an Android device ")
        appendLine("with a Linux sandbox (Alpine, aarch64).")
        appendLine()
        appendLine(
            "You are NOT a general assistant in this turn. You have exactly one job, " +
                "described below, and you must answer with the handoff block it specifies. " +
                "Do not answer the user's request directly, do not do another role's work, " +
                "and do not summarise the whole task — the run's other agents depend on you " +
                "doing only your part and reporting it in the agreed format.",
        )

        val notes = allowedTools.mapNotNull { TOOL_NOTES[it] }
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("Your tools (the schema contains ONLY these — nothing else is callable):")
            notes.forEach { appendLine(it) }
        }

        if (workspaceDir != null) {
            appendLine()
            appendLine(
                "Shared workspace for this run: $workspaceDir — every agent in this run reads " +
                    "and writes the SAME directory, so a file you create there is what the " +
                    "reviewer will inspect. Write deliverables there, not to /tmp.",
            )
        }

        appendLine()
        append(roleContract)
    }

    /** Rough token estimate (~4 chars/token). For diagnostics and tests. */
    fun approximateTokens(prompt: String): Int = prompt.length / 4
}
