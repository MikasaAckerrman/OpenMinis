package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String? = null,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,        // milliseconds
    @ColumnInfo(name = "updated_at") val updatedAt: Long,        // milliseconds
    val category: String? = null,
    @ColumnInfo(name = "last_message") val lastMessage: String? = null,
    @ColumnInfo(name = "model_binding") val modelBinding: String? = null,
    // iOS parity fields:
    @ColumnInfo(name = "source") val source: String? = null,             // e.g. "shortcut", "share"
    @ColumnInfo(name = "memory_enabled") val memoryEnabled: Int = 1,     // 1=on, 0=off
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,          // milliseconds, null=not pinned
    @ColumnInfo(name = "edit_count") val editCount: Int = 0,             // message edit counter
    // T239: per-session thinking-mode override. null = unset (use the
    // current model/group default — i.e. existing pre-T239 behaviour, which
    // is OFF on Android today). Non-null is one of ThinkingLevel.name
    // ("OFF"/"LOW"/"MEDIUM"/"HIGH"/"XHIGH") and represents an explicit user
    // choice that survives cold-start.
    @ColumnInfo(name = "thinking_override") val thinkingOverride: String? = null,

    /**
     * [T-agent-graph-showcase] Non-null when this session belongs to a
     * multi-agent run: the value is the run's taskId. The main chat list hides
     * these; the showcase session for that taskId links to them.
     *
     * Why a column and not a naming convention on `source`: `source` already
     * carries provenance ("shortcut", "share", "debug") and overloading it
     * would make "which run did this belong to" unanswerable in SQL.
     */
    @ColumnInfo(name = "agent_run_id") val agentRunId: String? = null,

    /**
     * The agent role that owns this session, e.g. "SENIOR_IMPLEMENTER". Lets the
     * child list show WHO each session is instead of a row of untitled chats.
     */
    @ColumnInfo(name = "agent_role") val agentRole: String? = null,

    /**
     * True (1) for the single showcase session of a run — the one the user reads.
     * It aggregates progress from the worker sessions and IS visible in the main
     * list, standing in for the whole run.
     */
    @ColumnInfo(name = "is_agent_showcase") val isAgentShowcase: Int = 0,
)
