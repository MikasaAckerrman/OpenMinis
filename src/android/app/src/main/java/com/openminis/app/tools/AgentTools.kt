package com.openminis.app.tools

import com.openminis.app.browser.BrowserAction
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/**
 * Central registry of all agent tool definitions.
 * Returns provider-agnostic AgentToolDefinition list used by the agent loop.
 * Tool definitions aligned with iOS AIChatViewModel.makeAgentTools().
 */
object AgentTools {

    /**
     * [T-agent-graph] Canonical tool names, so a caller can express an
     * allowlist without hardcoding strings. Kept in one place because the
     * graph config (`allowedTools` on an AgentNode) is written by users and
     * validated against this set.
     */
    val ALL_TOOL_NAMES: Set<String> = setOf(
        "shell_execute",
        FileReadTool.NAME,
        FileWriteTool.NAME,
        FileEditTool.NAME,
        ReadImageTool.NAME,
        "browser_use",
        "memory_write",
        "memory_get",
    )

    /**
     * Accept the loose aliases people naturally write in a graph JSON
     * (`shell`, `browser`, `memory`) and map them onto the real tool names.
     * Unknown input passes through unchanged so validation can reject it.
     */
    fun canonicalToolName(raw: String): String = when (raw.trim().lowercase()) {
        "shell", "shell_execute", "shell-execute" -> "shell_execute"
        "file_read", "file-read", "read" -> FileReadTool.NAME
        "file_write", "file-write", "write" -> FileWriteTool.NAME
        "file_edit", "file-edit", "edit" -> FileEditTool.NAME
        "read_image", "read-image", "image" -> ReadImageTool.NAME
        "browser", "browser_use", "browser-use" -> "browser_use"
        "memory_write", "memory-write" -> "memory_write"
        "memory_get", "memory-get" -> "memory_get"
        // `memory` alone means both halves; callers expand it before this point.
        else -> raw.trim()
    }

    /**
     * Whether [toolName] is permitted by [allowedTools]. Empty/null allowlist
     * means unrestricted. Shared by the schema filter in [makeAgentTools] and
     * the executor-level check in ChatViewModel, so both agree on aliases.
     */
    fun isToolPermitted(toolName: String, allowedTools: List<String>?): Boolean {
        val allow = expandAllowlist(allowedTools) ?: return true
        return canonicalToolName(toolName) in allow
    }

    /**
     * Resolve an allowlist to canonical names, or null when unrestricted.
     * `memory` expands to both memory halves.
     */
    fun expandAllowlist(allowedTools: List<String>?): Set<String>? =
        allowedTools
            ?.takeIf { it.isNotEmpty() }
            ?.flatMap { raw ->
                if (raw.trim().lowercase() == "memory") {
                    listOf("memory_write", "memory_get")
                } else {
                    listOf(canonicalToolName(raw))
                }
            }
            ?.toSet()

    fun makeAgentTools(
        supportsImageInput: Boolean = true,
        // [T-memory-toggle-gates-injection-and-tools-android] When the
        // user has turned memory off (via /memory or
        // Settings/SessionMemorySheet), drop both memory_write and
        // memory_get from the schema entirely so the model can't even
        // attempt those calls. Mirrors the iOS gate at
        // AIChatViewModel.makeAgentTools(memoryEnabled:).
        memoryEnabled: Boolean = true,
        /**
         * [T-agent-graph] Per-session tool allowlist. null or empty = every
         * tool (existing behaviour, all normal chat sessions). When non-empty,
         * ONLY the listed tools reach the model's schema — a multi-agent node
         * with `allowedTools: [file_read, shell]` physically cannot call
         * file_write, instead of merely being told not to in its prompt.
         *
         * Aliases are accepted (`shell`, `browser`, `memory`); see
         * [canonicalToolName]. `memory` expands to both memory halves.
         */
        allowedTools: List<String>? = null,
        /**
         * [T-scoped-agent-toggles] Session-scoped subagents gate: the caller
         * (ChatViewModel) resolves session-override → legacy global. Defaults
         * to the global pref so non-session callers (DebugRPCHandler) keep
         * the previous behaviour.
         */
        subagentsEnabled: Boolean = com.openminis.app.data.SubagentPrefs.isEnabled(),
        /**
         * [T-letta-core-memory] App-level core-memory gate (CoreMemoryPrefs,
         * settings row — NOT a per-session toggle: this is infrastructure).
         * Off → the block tools leave the schema entirely AND the
         * effectiveAgentHistory injection is skipped (zero cost), exactly
         * the [T-subagent-gate] discipline.
         */
        coreMemoryEnabled: Boolean = com.openminis.app.data.CoreMemoryPrefs.isEnabled(),
        /**
         * [T-root-shell] Master switch for the kernel-root tool. Default OFF
         * (RootShellPrefs); ON only by an explicit user decision in Settings.
         * Off → the tool leaves the schema entirely (the model cannot even
         * attempt a root call).
         */
        rootShellEnabled: Boolean = com.openminis.app.data.RootShellPrefs.isEnabled(),
    ): List<AgentToolDefinition> {
        val allow = expandAllowlist(allowedTools)

        fun permitted(name: String): Boolean = allow == null || name in allow

        return buildList {
            if (permitted("shell_execute")) add(shellExecuteDefinition())
            if (permitted(FileReadTool.NAME)) add(FileReadTool.definition())
            if (permitted(FileWriteTool.NAME)) add(FileWriteTool.definition())
            if (permitted(FileEditTool.NAME)) add(FileEditTool.definition())
            if (supportsImageInput && permitted(ReadImageTool.NAME)) {
                add(ReadImageTool.definition())
            }
            if (permitted("browser_use")) add(browserUseDefinition())
            // [T-web-search] First-class web search (Tavily): one call,
            // clean results — the model no longer pays a browser session
            // for plain facts. Ungated like session_gc: read-only.
            if (permitted(WebSearchTool.NAME)) add(WebSearchTool.definition())
            // [T-spawn-subagent] Claude Code + OpenAI Agents SDK pattern:
            // the LLM can delegate subtasks to specialist agents at runtime.
            // [T-subagent-gate] User decision 23.09.2026: subagents are
            // OPT-IN — the tools leave the schema entirely when the toggle
            // is off (the model can't even attempt a spawn call), exactly
            // like the memory gate above. turn_timer is NOT gated: it is a
            // per-conversation budget tool, not the multi-agent machinery.
            // [T-scoped-agent-toggles] the gate is now session-scoped: the
            // ChatViewModel passes its resolved per-session value, so
            // enabling subagents in one chat leaves other chats' schemas
            // clean. The parameter default keeps the legacy global for
            // non-session callers.
            if (subagentsEnabled) {
                if (permitted(com.openminis.app.tools.SubagentTools.SPAWN_TOOL_NAME)) {
                    add(com.openminis.app.tools.SubagentTools.spawnSubagentDefinition())
                }
                if (permitted(com.openminis.app.tools.SubagentTools.SPAWN_MANY_TOOL_NAME)) {
                    add(com.openminis.app.tools.SubagentTools.spawnManyDefinition())
                }
                if (permitted(com.openminis.app.tools.SubagentTools.LIST_AGENTS_TOOL_NAME)) {
                    add(com.openminis.app.tools.SubagentTools.listAgentsDefinition())
                }
                if (permitted(com.openminis.app.tools.SubagentTools.RUN_GRAPH_TOOL_NAME)) {
                    add(com.openminis.app.tools.SubagentTools.runGraphDefinition())
                }
                // [T-task-board] The board view rides with the spawn tools:
                // delegation without sight of the team's history is how
                // duplicate spawns happen — same tool gate, same session scope.
                if (permitted(com.openminis.app.tools.SubagentTools.TASK_BOARD_TOOL_NAME)) {
                    add(com.openminis.app.tools.SubagentTools.taskBoardDefinition())
                }
            }
            if (permitted(com.openminis.app.tools.TurnTimerTool.NAME)) {
                add(com.openminis.app.tools.TurnTimerTool.definition())
            }
            if (memoryEnabled) {
                if (permitted("memory_write")) add(memoryWriteDefinition())
                if (permitted("memory_get")) add(memoryGetDefinition())
                // [T-supermemory-tool] Semantic tier for the model: the
                // local supermemory service (associative recall over every
                // distilled turn + compacted knowledge). Complements
                // memory_get (keyword scan of the daily logs) — this is
                // meaning-based recall.
                if (permitted("supermemory_search")) add(supermemorySearchDefinition())
            }
            // [T-session-gc] Session weight + safe GC: ungated — it is
            // read-only by default (dry-run report) and its write mode is
            // lossless-by-construction (offload + stub, never a delete).
            if (permitted("session_gc")) add(sessionGcDefinition())
            // [T-bg-tasks] Background process tools — start/inspect/kill
            // long-running commands. Ungated like session_gc: the executor
            // reuses the shell policy gates (jail write-contract +
            // destructive screening) before launching anything.
            for (def in com.openminis.app.tools.BgTaskTools.definitions()) {
                if (permitted(def.name)) add(def)
            }
            // [T-letta-core-memory] Letta-style core blocks: the gate is
            // app-level (CoreMemoryPrefs, Memory management settings row),
            // independent of the daily-log memory gate above — a user can
            // keep the searchable daily log off while running curated
            // always-in-context blocks, and vice versa.
            if (coreMemoryEnabled) {
                if (permitted("memory_blocks_view")) add(memoryBlocksViewDefinition())
                if (permitted("memory_blocks_edit")) add(memoryBlocksEditDefinition())
            }
            // [T-root-shell] Kernel-root execution — the most privileged
            // surface: master-gated (default OFF), plus per-command
            // destructive screening at the executor (dialog for dangerous
            // commands even when the master gate is armed).
            if (rootShellEnabled) {
                if (permitted("root_shell")) add(rootShellDefinition())
            }
        }
    }

    // Aligned with iOS AIChatViewModel.swift:4982-4993
    private fun shellExecuteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "shell_execute",
        description = "Execute a command in an isolated Linux process (Alpine Linux via PRoot). " +
            "The command runs via /bin/sh -c with stdout and stderr merged. " +
            "Each invocation spawns a fresh process — there is no shared terminal session. " +
            "Default timeout is 15 minutes.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Install Python data analysis packages', 'List files in home directory'). Use the same language as the user."),
            "command" to AgentToolParam("string", "The shell command to execute. Supports multi-line commands directly — no special escaping needed. Keep under 1000 chars; for longer scripts, write to a file with file_write first, then run it."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds (default: 900). Use a larger value for long-running commands like package installs."),
            "delay" to AgentToolParam("integer", "Delay in seconds before execution begins. The tool blocks the agent flow during this wait WITHOUT occupying the shell, so other concurrent tasks can use it. Use this instead of sleep commands to avoid resource contention."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "timeout", "delay"),
    )

    // Aligned with iOS AIChatViewModel.swift browser_use definition
    private fun browserUseDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "browser_use",
        description = "Control a web browser with up to 3 tabs. " +
            "Do NOT use this tool for minis:// action URLs (open_terminal, views, settings) — those are app deep links, use Markdown links in chat instead. " +
            "The browser supports both web URLs and minis:// resource URLs. Use minis:// URLs to preview session files (e.g. navigate to minis://workspace/index.html). " +
            "Sub-resources (JS, CSS, images, fonts) referenced via minis:// absolute paths or relative paths within HTML pages resolve correctly. " +
            "Use navigate to open URLs, screenshot to see the page (returns an image), " +
            "click/type to interact with elements, get_text/get_readable to extract content, " +
            "scroll to navigate long pages, scroll_and_collect to scroll through infinite-scroll/virtual-rendered pages (like Twitter/X timelines) and accumulate unique content items across scroll positions in a single call, " +
            "find_elements to discover interactive elements, " +
            "get_page_info for page metadata, get_backbone to get a structural overview of the page DOM as a simplified tree, " +
            "fetch to download files/resources using the page's session (returns metadata and a minis:// URL), " +
            "new_tab to open an additional tab, close_tab to close a tab, and list_tabs to see all open tabs. " +
            "Use set_viewport with viewport_width + viewport_height to override the viewport for the current session (e.g. before screenshotting a 1920×1080 HTML composition that would otherwise be cropped to the phone viewport); pass reset=true to drop the session override and fall back to the global browser setting. " +
            "Use get_cookies to retrieve cookies for the current page URL / current site root domain only (including HttpOnly cookies). get_cookies supports optional 'keywords' (filter by cookie name) and 'fuzzy' (true=contains match, false=exact match, default true). It returns only a summary and an offload env file path — raw cookie values are NOT included in the tool response. To reuse cookies in shell commands: `. /var/minis/offloads/env_cookies_xxx.sh && command`. You may define alias variables when needed. " +
            "Use set_cookies to write cookies into the current page's cookie store via the native cookie store (so even HttpOnly cookies, which JS cannot set, land). Pass a 'cookies' array of objects, each with name + value (required) and optional domain (defaults to the current page host), path (defaults to '/'), secure, http_only, and expires (Unix timestamp in seconds; omit for a session cookie). " +
            "Use wait_for_dom_stable to wait until the page DOM stops changing (useful after navigation or interactions that trigger async data loading — polls every 0.5s, resolves when mutation rate gradient is stable for 3+ intervals, default timeout 10s). " +
            "Use tab_id to target a specific tab (defaults to the most recently used tab).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Open Wikipedia homepage', 'Take screenshot of current page'). Use the same language as the user."),
            "action" to AgentToolParam("string", "The browser action to perform",
                enumValues = BrowserAction.allValues),
            "url" to AgentToolParam("string", "URL to navigate to (for navigate action) or resource to download (for fetch action)"),
            "selector" to AgentToolParam("string", "CSS selector for targeting elements (click, type, get_text, scroll, hover, find_elements). For scroll: specify a scrollable container to scroll (e.g. 'div.timeline'); if omitted, auto-detects the best scrollable element."),
            "text" to AgentToolParam("string", "Text to type (for type action)"),
            "coordinate_x" to AgentToolParam("integer", "X coordinate for click (alternative to selector)"),
            "coordinate_y" to AgentToolParam("integer", "Y coordinate for click (alternative to selector)"),
            "direction" to AgentToolParam("string", "Scroll direction", enumValues = listOf("up", "down")),
            "amount" to AgentToolParam("integer", "Scroll amount in pixels (default: 500)"),
            "script" to AgentToolParam("string", "JavaScript code to execute (for execute_js action). The script runs inside an async function wrapper — `await` and top-level `return` are both supported (e.g. `var r = await fetch(url); return await r.json()`)."),
            "user_agent" to AgentToolParam("string", "User agent profile to switch to", enumValues = listOf("desktop_chrome", "mobile_chrome")),
            "max_depth" to AgentToolParam("integer", "Maximum tree depth for get_backbone (default: 5)"),
            "scroll_count" to AgentToolParam("integer", "Number of scroll steps for scroll_and_collect (default: 10, max: 20). Each step scrolls by 'amount' pixels and waits for new content."),
            "item_selector" to AgentToolParam("string", "CSS selector for individual content items in scroll_and_collect (e.g. 'article', '[data-testid=\"tweet\"]'). If omitted, auto-detects repeated elements."),
            "tab_id" to AgentToolParam("integer", "Target tab ID (optional, defaults to most recently used tab). Use list_tabs to see available tabs."),
            "keywords" to AgentToolParam("string", "Filter cookies by name (for get_cookies). A space-separated string or array of strings. With fuzzy=true (default), ALL keywords must appear in the cookie name (case-insensitive). With fuzzy=false, cookie name must exactly equal any one of the provided keywords (case-insensitive). Omit to return all cookies for the current site."),
            "fuzzy" to AgentToolParam("boolean", "Whether keyword matching is fuzzy (contains-all) or exact-any (for get_cookies, default: true)."),
            "cookies" to AgentToolParam("string", "For set_cookies: a JSON array of cookie objects to write. Pass it as a JSON array (a JSON-encoded string of the array is also accepted). Each object: {\"name\": str (required), \"value\": str (required), \"domain\": str (optional, defaults to current page host), \"path\": str (optional, defaults to \"/\"), \"secure\": bool (optional), \"http_only\": bool (optional — sets an HttpOnly cookie that JS cannot read/set), \"expires\": int (optional, Unix timestamp in seconds; omit for a session cookie)}. Field-name variants from common cookie exports are accepted: httpOnly (=http_only), expirationDate (=expires), sameSite, and case/camel variants — so you can paste cookies verbatim from browser extensions (EditThisCookie / Cookie-Editor) or Playwright/Puppeteer storage."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds for wait_for_dom_stable (default: 10). The action polls every 0.5s and resolves when DOM mutation rate stabilizes."),
            "viewport_width" to AgentToolParam("integer", "Viewport width in CSS pixels for set_viewport (e.g. 1920). Required together with viewport_height unless reset=true."),
            "viewport_height" to AgentToolParam("integer", "Viewport height in CSS pixels for set_viewport (e.g. 1080). Required together with viewport_width unless reset=true."),
            "reset" to AgentToolParam("boolean", "For set_viewport: when true, clear the session-level viewport override and fall back to the global browser setting."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "tab_id", "url", "selector", "text", "coordinate_x", "coordinate_y", "direction", "amount", "scroll_count", "item_selector", "script", "user_agent", "max_depth", "keywords", "fuzzy", "cookies", "timeout", "viewport_width", "viewport_height", "reset"),
    )

    // Aligned with iOS AIChatViewModel.swift:5059-5067
    private fun memoryWriteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_write",
        description = "Write a memory entry to today's daily log (YYYY-MM-DD.md). Memories persist across all sessions. " +
            "Each entry is prepended with a timestamp. " +
            "Save: user preferences, recurring patterns, key facts, project conventions, reusable knowledge. " +
            "Avoid saving passwords, API keys, tokens, or secrets unless the user explicitly confirms after being warned. " +
            "Keep entries concise and general-purpose. GLOBAL.md is read-only (user-maintained via Settings).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Save user preference for Python', 'Note today's project context'). Use the same language as the user."),
            "content" to AgentToolParam("string", "The memory content to write. Use concise Markdown with a short heading (## Topic) and context about what was done/learned."),
        ),
        required = listOf("tool_title", "content"),
        propertyOrdering = listOf("tool_title", "content"),
    )

    // Aligned with iOS AIChatViewModel.swift:5069-5078
    private fun memoryGetDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_get",
        description = "Retrieve memories from persistent storage. Supports keyword-based fuzzy search across memory files. " +
            "Returns matching lines with surrounding context. Use this to recall previous knowledge, user preferences, or past notes.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Recall user preferences', 'Search past notes'). Use the same language as the user."),
            "scope" to AgentToolParam("string", "Memory scope to search: 'daily' for daily logs only, 'all' for daily logs + GLOBAL.md.", enumValues = listOf("daily", "all")),
            "keywords" to AgentToolParam("string", "Space-separated keywords for fuzzy matching (e.g. 'python preference' or 'API key setup'). All keywords must appear in a line or its surrounding context for a match. Leave empty to return full memory files."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "scope", "keywords"),
    )

    /**
     * [T-letta-core-memory] Letta-style core memory blocks — a SMALL set of
     * standing facts injected into EVERY request payload (4th layer of the
     * agent history). Unlike memory_write (append-only daily log searched on
     * demand), core blocks are always in-context: the model reads them as
     * standing instructions and edits them when the facts change.
     */
    private fun memoryBlocksViewDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_blocks_view",
        description = "List ALL core-memory blocks (id, label, value, pinned, last edited). Core blocks are standing facts " +
            "injected into every request. Use this when the injection header was truncated by the budget, " +
            "or to check a block's exact current value before editing.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of this tool call, shown to the user (e.g. 'List core memory blocks'). Use the same language as the user."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
    )

    /** [T-letta-core-memory] Create/update/delete a core-memory block by id. */
    private fun memoryBlocksEditDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_blocks_edit",
        description = "Create, update, or delete a core-memory block. Core blocks are standing facts in every request — " +
            "keep them few, stable and high-signal (user preferences, project conventions, key constraints). " +
            "To CREATE: pass a NEW id (b1..b16), label, value. To UPDATE: pass an existing id with changed fields. " +
            "To DELETE: action=delete with the id. Caps: 16 blocks, 4000 chars per value — oversized edits are REJECTED " +
            "with an explicit error (shorten, don't truncate silently).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of this tool call, shown to the user (e.g. 'Update core memory: user prefers Kotlin'). Use the same language as the user."),
            "action" to AgentToolParam("string", "upsert (create or update) or delete", enumValues = listOf("upsert", "delete")),
            "id" to AgentToolParam("string", "Block id: existing to update/delete, new (b1..b16) to create."),
            "label" to AgentToolParam("string", "One-line title (max 80 chars), e.g. 'Project conventions'."),
            "value" to AgentToolParam("string", "The fact itself (max 4000 chars). Omit for delete."),
            "pinned" to AgentToolParam("boolean", "Pinned blocks survive injection-budget cuts and lead the header."),
        ),
        required = listOf("tool_title", "action", "id"),
        propertyOrdering = listOf("tool_title", "action", "id", "label", "value", "pinned"),
    )

    /** [T-supermemory-tool] Semantic search over the local supermemory service. */
    private fun supermemorySearchDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "supermemory_search",
        description = "Semantic (meaning-based) search over the long-term associative memory store. " +
            "Unlike memory_get (keyword scan of daily logs), this recalls by MEANING across every " +
            "distilled turn and compacted knowledge archive. Use when keyword search misses: " +
            "'what did we decide about background freezes' finds it even without the exact words. " +
            "Empty result = nothing semantically close (or the local service is down — prefer " +
            "memory_get then).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of this tool call, shown to the user (e.g. 'Search long-term memory'). Use the same language as the user."),
            "query" to AgentToolParam("string", "Natural-language query, e.g. 'решение по заморозке фона' or 'supermemory port contract'."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query"),
    )

    /**
     * [T-session-gc] Safe session garbage collection + weight report. The
     * agent answers "how heavy is this session and what can be cleaned
     * WITHOUT losing anything": dry-run (default) reports the weight and
     * the offloadable candidates; confirm=true rewrites fat OLD tool-result
     * bodies into on-disk offloads (lossless — file_read reaches them),
     * never deleting rows, never touching the protected tail, compact
     * markers, or failed results.
     */
    private fun sessionGcDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "session_gc",
        description = "Report this session's weight and optionally collect garbage SAFELY. " +
            "Default (no confirm): dry-run report — rows, chars by role, tool-result share, " +
            "offloadable bytes. With confirm=true: every fat OLD successful tool-result body " +
            "is moved verbatim to the session's offloads dir and replaced by a tiny stub " +
            "(lossless — the full text stays file_read-able; the session shrinks on the wire " +
            "AND in the DB). HARD SAFETY: never deletes messages; the last 6 user turns and " +
            "everything after them are never touched; compact markers and FAILED tool results " +
            "are never rewritten; already-cleaned parts are skipped (idempotent).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary (e.g. 'Weigh and clean session'). Use the user's language."),
            "confirm" to AgentToolParam("boolean", "false (default) = dry-run report only; true = execute the offload+rewrite."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "confirm"),
    )

    /**
     * [T-root-shell] Kernel-root command execution for the model. The user
     * arms this explicitly (Settings → Background & Notifications → Root
     * shell; default OFF — the tool leaves the schema when disarmed).
     *
     * Contract differences vs shell_execute (the PRoot sandbox):
     *   • runs on the ANDROID side with `su 0` (KernelSU context u:r:ksu:s0):
     *     real paths are /data/data/<pkg>/files/…, /sdcard, /system, /data/adb —
     *     NOT the sandbox's /var/minis/… view;
     *   • every command passes DestructiveCommandPolicy; destructive ones
     *     additionally require the interactive approval dialog;
     *   • root unavailable (KSU down after reboot) → a clean error with the
     *     recovery hint, never a crash.
     */
    private fun rootShellDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "root_shell",
        description = "Execute a shell command with KERNEL ROOT (su 0) on the Android side. " +
            "Use ONLY when a task genuinely needs privileged access: system settings (settings/device_config), " +
            "process inspection (dumpsys, /proc), cgroup/power management, package management (pm), " +
            "reading diagnostics data of other apps, KSU module operations. " +
            "IMPORTANT: this is NOT the sandbox shell — paths are Android-real (/data/data, /system, /sdcard); " +
            "/var/minis/... does NOT exist here (the app's own files live at /data/data/com.openminis.app.clone/files/...). " +
            "Destructive commands (rm/kill/dd/format/flash…) trigger an interactive user approval dialog — " +
            "prefer read-only diagnostics; announce what you are about to change and why. " +
            "If root is unavailable (KSU not started), the result says so — tell the user to run root.sh and retry.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of this tool call, shown to the user (e.g. 'Check cgroup freeze state'). Use the same language as the user."),
            "command" to AgentToolParam("string", "The shell command to run as root. Android-side paths; NO sandbox paths. Quoting is safe — the command is executed from a script file."),
            "timeout_s" to AgentToolParam("number", "Timeout in seconds, 10..300 (default 60)."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "timeout_s"),
    )
}
