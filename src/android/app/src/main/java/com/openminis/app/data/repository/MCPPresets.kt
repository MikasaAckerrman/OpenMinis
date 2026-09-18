package com.openminis.app.data.repository

/**
 * Built-in OAuth/stdio presets for popular MCP-compatible services. Selecting
 * one in MCPIntegrationsScreen auto-fills the form (command, args, OAuth
 * endpoints, scopes) so the user only provides their client_id/secret and
 * presses Sign-in.
 *
 * Each preset returns a partial MCPServerConfig — the UI merges it with user
 * input for fields that vary per-user (client_id, secret, env values).
 */
object MCPPresets {
    /** Monotonically increasing counter to make ids unique even if nanos collide. */
    private var idCounter: Long = 0

    data class Preset(
        val id: String,
        val displayName: String,
        val description: String,
        /** 24×24 favicon URL — loaded async into UI. Null = use generic icon. */
        val iconUrl: String? = null,
        /** STDIO transport. If null → URL transport. */
        val command: String? = null,
        val args: List<String> = emptyList(),
        /** HTTP transport endpoint for remote MCPs. */
        val url: String? = null,
        /** OAuth config presets — endpoints and scopes. client_id is user-supplied. */
        val authUrl: String? = null,
        val tokenUrl: String? = null,
        val scopes: String? = null,
    )

    val all: List<Preset> = listOf(
        // ── Google Workspace (Drive / Docs / Sheets / Calendar) ──
        Preset(
            id = "google-workspace",
            displayName = "Google Workspace",
            description = "Drive, Docs, Sheets, Calendar через официальный MCP",
            iconUrl = "https://www.gstatic.com/devrel-devsite/prod/v0a3d92e3a1c74c9e3a7e7e3e9a3c6b8a/google-g-icon.png",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-google-workspace"),
            authUrl = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenUrl = "https://oauth2.googleapis.com/token",
            scopes = "openid email https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/documents https://www.googleapis.com/auth/spreadsheets https://www.googleapis.com/auth/calendar.readonly",
        ),

        // ── Notion ──
        Preset(
            id = "notion",
            displayName = "Notion",
            description = "Страницы, базы данных, блоки Notion через официальный MCP",
            iconUrl = "https://www.notion.so/images/logo-ios.png",
            command = "npx",
            args = listOf("-y", "@notionhq/mcp-server-notion"),
            authUrl = "https://api.notion.com/v1/oauth/authorize",
            tokenUrl = "https://api.notion.com/v1/oauth/token",
            scopes = "read_content read_user",
        ),

        // ── GitHub (via gh CLI) ──
        Preset(
            id = "github",
            displayName = "GitHub",
            description = "Issues, PRs, репозитории, actions через gh CLI",
            iconUrl = "https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-github"),
            scopes = "repo read:user",
        ),

        // ── Slack ──
        Preset(
            id = "slack",
            displayName = "Slack",
            description = "Каналы, сообщения, реакции через официальный MCP",
            iconUrl = "https://a.slack-edge.com/80588/marketing/img/meta/slack_hash_256.png",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-slack"),
            scopes = "channels:read chat:write users:read",
        ),

        // ── Linear ──
        Preset(
            id = "linear",
            displayName = "Linear",
            description = "Issues, проекты, циклы через официальный MCP",
            iconUrl = "https://linear.app/favicon.svg",
            command = "npx",
            args = listOf("-y", "linear-mcp-server"),
            scopes = "read write",
        ),

        // ── Figma (official) ──
        Preset(
            id = "figma",
            displayName = "Figma",
            description = "Файлы, фреймы, компоненты Figma",
            iconUrl = "https://static.figma.com/app/icon/1/icon-192.png",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-figma"),
            scopes = "files:read",
        ),

        // ── Postgres ──
        Preset(
            id = "postgres",
            displayName = "PostgreSQL",
            description = "SQL-запросы к PostgreSQL через STDIO (без OAuth)",
            iconUrl = null,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-postgres", "postgresql://localhost/mydb"),
        ),

        // ── Filesystem ──
        Preset(
            id = "filesystem",
            displayName = "Filesystem",
            description = "Чтение/запись файлов в указанной директории",
            iconUrl = null,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/var/minis/workspace"),
        ),
    )

    fun findById(id: String): Preset? = all.find { it.id == id }

    /**
     * Convert a preset to a [com.openminis.app.data.repository.MCPRepository.MCPServerConfig]
     * ready for `mcpRepository.add()`. The user fills in client_id/secret
     * afterwards (OAuth) or the API key/username (other transports).
     */
    fun toConfig(preset: Preset): com.openminis.app.data.repository.MCPRepository.MCPServerConfig {
        // id = preset + nanos + counter — unique even if user taps same preset twice within 1ms.
        // nanoTime + counter avoids System.currentTimeMillis() collisions under rapid taps.
        id = preset.id + "-" + System.nanoTime().toString(36) + "-" + (++idCounter).toString(36)
        return com.openminis.app.data.repository.MCPRepository.MCPServerConfig(
            id = id,
            // display name stored in `note` (MCPServerConfig has no `name` field;
            // UI shows server.id as primary label and note as subtitle)
            note = preset.displayName,
            command = preset.command,
            args = preset.args,
            url = preset.url,
            iconUrl = preset.iconUrl,
            enabled = true,
            oauth = if (preset.authUrl != null && preset.tokenUrl != null) {
                com.openminis.app.mcp.oauth.MCPOAuthConfig(
                    clientId = "",
                    authorizationEndpoint = preset.authUrl,
                    tokenEndpoint = preset.tokenUrl,
                    scopes = preset.scopes,
                )
            } else null,
        )
    }
}
