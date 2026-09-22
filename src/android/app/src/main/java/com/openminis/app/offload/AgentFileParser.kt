package com.openminis.app.offload

/**
 * [T-agent-file] Parser for user-defined subagent files — the Codex v2
 * agent-file pattern (agents as config, no code) adapted to our stack:
 * one markdown file per agent under /var/minis/agents/, YAML-ish
 * frontmatter + body.
 *
 * Format:
 * ```
 * ---
 * name: api-auditor
 * description: Audits REST APIs for auth, pagination and error handling
 * modelRole: reviewer            # optional: planner|analyst|architect|coder|reviewer|tester
 * model: <modelEntryId>          # optional: pin a specific model entry (beats modelRole)
 * tools: shell_execute, file_read, file_write
 * maxTurns: 10
 * ---
 * You are an API auditor. Your ONE job: ...
 * (body = developer_instructions, injected verbatim)
 * ```
 *
 * Pure by design (no Android imports) so the format contract is
 * unit-testable off-device.
 */
object AgentFileParser {

    data class AgentFile(
        /** Spawn id: what the orchestrator passes as role="custom:<name>". */
        val name: String,
        /** One line for the catalog / orchestrator's choice. */
        val description: String,
        /** Optional model-role key (planner|analyst|architect|coder|reviewer|tester). */
        val modelRole: String? = null,
        /** Optional explicit model entry id — wins over [modelRole]. */
        val modelEntryId: String? = null,
        /** Tool allowlist; null = the default tools for the runner role. */
        val tools: List<String>? = null,
        /** Tool-call budget for the spawned run. */
        val maxTurns: Int = DEFAULT_MAX_TURNS,
        /** The whole body — developer instructions, injected verbatim. */
        val instructions: String,
        val fileName: String,
    ) {
        companion object {
            const val DEFAULT_MAX_TURNS = 8
        }
    }

    private val VALID_MODEL_ROLES = setOf(
        "planner", "analyst", "architect", "coder", "reviewer", "tester",
    )

    private val KNOWN_TOOL_NAMES = setOf(
        "shell_execute", "file_read", "file_write", "file_edit",
        "read_image", "browser_use", "memory_write", "memory_get",
    )

    fun parse(fileName: String, content: String): AgentFile? {
        val text = content.trim()
        if (text.isEmpty()) return null

        val (frontmatterRaw, body) = splitFrontmatter(text)
        val fields = parseFrontmatter(frontmatterRaw)

        val name = (fields["name"] ?: fileName.removeSuffix(".md")).trim()
        if (name.isBlank() || name.contains(':')) return null

        val description = fields["description"]?.trim().orEmpty()

        val modelRole = fields["modelRole"]?.trim()?.lowercase()
            ?.takeIf { it in VALID_MODEL_ROLES }

        val modelEntryId = fields["model"]?.trim()?.takeIf { it.isNotBlank() }

        val tools = fields["tools"]
            ?.split(',')
            ?.map { canonicalTool(it.trim()) }
            ?.filter { it in KNOWN_TOOL_NAMES }
            ?.takeIf { it.isNotEmpty() }

        val maxTurns = fields["maxTurns"]?.trim()?.toIntOrNull()
            ?.takeIf { it in 1..64 }
            ?: AgentFile.DEFAULT_MAX_TURNS

        val instructions = body.trim()
        if (instructions.isEmpty()) return null

        return AgentFile(
            name = name,
            description = description,
            modelRole = modelRole,
            modelEntryId = modelEntryId,
            tools = tools,
            maxTurns = maxTurns,
            instructions = instructions,
            fileName = fileName,
        )
    }

    /** Split leading `---` frontmatter block from the body. */
    internal fun splitFrontmatter(text: String): Pair<String, String> {
        if (!text.startsWith("---")) return "" to text
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return "" to text
        val frontmatter = text.substring(3, end).trim()
        // Skip the closing fence (---) and any following blank line.
        var bodyStart = end + 4
        while (bodyStart < text.length && text[bodyStart] == '\n') bodyStart++
        return frontmatter to text.substring(bodyStart)
    }

    /** Flat `key: value` lines; comments (#) and blanks ignored. */
    internal fun parseFrontmatter(frontmatter: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (rawLine in frontmatter.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            var value = line.substring(idx + 1).trim()
            // Strip balanced quotes — frontmatter may copy YAML examples.
            if (value.length >= 2 &&
                (value.first() == '"' || value.first() == '\'') &&
                value.first() == value.last()
            ) {
                value = value.substring(1, value.length - 1)
            }
            if (key.isNotBlank() && value.isNotBlank()) map[key] = value
        }
        return map
    }

    /** Accept friendly aliases; unknown tools are dropped by the caller. */
    private fun canonicalTool(raw: String): String = when (raw.lowercase()) {
        "shell", "sh", "bash" -> "shell_execute"
        "read", "read_file" -> "file_read"
        "write", "write_file" -> "file_write"
        "edit", "edit_file" -> "file_edit"
        "image", "read_image" -> "read_image"
        "browser" -> "browser_use"
        else -> raw.lowercase()
    }
}
