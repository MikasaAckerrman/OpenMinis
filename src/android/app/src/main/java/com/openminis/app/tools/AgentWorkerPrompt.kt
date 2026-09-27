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
        // [T-subagent-nesting] The delegation trio — only the orchestrator
        // and reviewers have these; the notes state the traps that actually
        // waste turns (incomplete task text, invisible workers, duplicate
        // spawns of already-done work).
        "spawn_subagent" to
            "- spawn_subagent: delegate ONE subtask to a specialist. The worker " +
            "sees ONLY the task text you pass — not this conversation — so include " +
            "file paths, the exact change and acceptance criteria. depends_on (JSON " +
            "array of task ids) makes the spawn wait for board tasks; unmet deps are " +
            "refused with the wait-set named.",
        "spawn_many" to
            "- spawn_many: batch of INDEPENDENT subtasks, up to 3 concurrently. " +
            "Tasks touching the same file are serialized automatically; state it in " +
            "the texts anyway. The result header includes the team's CROSS-TURN " +
            "history — re-delegate failed tails instead of redoing the plan.",
        "task_board" to
            "- task_board: view this team's durable board — every spawn this chat " +
            "ever made, statuses, results, unmet deps, dependency cycles. READ it " +
            "before continuing multi-step work from earlier turns.",
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

        // [T-env-snapshot] Operational context as FACTS, not inheritance: the
        // review's 'lost operational context' failure mode — one compact
        // block, ~50 tokens, the cheapest turn-saver in the prompt. NOT
        // included, on purpose: git branch / repo state (workers cannot git;
        // repo context belongs in the TASK text where it is explicit).
        appendLine()
        appendLine(
            "Environment: Android phone (aarch64), Alpine Linux via PRoot, BusyBox ash " +
                "shell (NOT bash). Tools: only those listed below. Writes: your own " +
                "workspace, /var/minis/attachments, /tmp — nothing global.",
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
            appendLine()
            appendLine(
                "Write contract: you may WRITE only /var/minis/workspace, " +
                    "/var/minis/attachments and /tmp — writes to /var/minis/shared, " +
                    "/var/minis/memory or /var/minis/skills are refused (they are global; " +
                    "parallel runs race there), and git index operations (add/commit/push/...) " +
                    "are refused for the same reason. Reads are unrestricted. Deliver your " +
                    "result as files in the workspace plus a handoff block naming them; the " +
                    "agent that spawned this run integrates and commits.",
            )
        }

        appendLine()
        append(roleContract)
    }

    /** Rough token estimate (~4 chars/token). For diagnostics and tests. */
    fun approximateTokens(prompt: String): Int = prompt.length / 4
}
