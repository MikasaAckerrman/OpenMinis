package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [T-task-board] The durable cross-turn task board (Cline Agent Teams
 * pattern, normalized Room schema — the review's spec, 27.09).
 *
 * One row per SPAWNED subagent run: spawn_subagent / spawn_many record it
 * here at spawn time and close it at completion, so the orchestrator that
 * returns next TURN — or next DAY — sees what its team already did, what
 * failed and why (via [TaskStatusHistoryEntity]) instead of relying on the
 * in-run memory of a chat transcript.
 *
 * Design notes against the alternatives (recorded in the review):
 *  - Cline keeps a flat task-board.json: no transactions, no consistent
 *    read under concurrent agents. Room on a WAL database gives both.
 *  - In-memory progress (AgentRunProgress) deliberately does not survive
 *    process death — that is correct for "is it running NOW" and this table
 *    is its complement: the durable ledger, not a mirror.
 *
 * Foreign keys: history and mailbox reference tasks with cascade/NULL so a
 * pruned task takes its derived rows with it. mission_log deliberately has
 * NO foreign key — it is the append-only ground truth and must record
 * events even for runs whose task row was pruned or never landed (a
 * failed insert into tasks must not lose the trace of WHY the run failed).
 */
@Entity(
    tableName = "agent_tasks",
    indices = [
        Index("team_id"),
        Index("status"),
    ],
)
data class AgentTaskEntity(
    /** The graph run's taskId — the same id AgentRunProgress and the trace file use. */
    @PrimaryKey val id: String,
    /** The SPAWNING session's id — one chat, one team. */
    @ColumnInfo(name = "team_id") val teamId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "description") val description: String,
    /** AgentRole name or "custom:<name>"; what the task asked for. */
    @ColumnInfo(name = "role_required") val roleRequired: String,
    /** PENDING | RUNNING | COMPLETED | FAILED | BLOCKED. */
    @ColumnInfo(name = "status") val status: String,
    /**
     * JSON array of task ids this task waited on. Stored as JSON (not a join
     * table) per the spec: dependency sets are written once at spawn and
     * never mutated, so the join table's normalization buys nothing here.
     * Readiness over the array is computed in Kotlin (AgentBoardLogic) —
     * json_each in SQL is not available below API 29 (minSdk 26).
     */
    @ColumnInfo(name = "depends_on_task_ids") val dependsOnTaskIds: String = "[]",
    /** The worker session bound to the run (filled at completion). */
    @ColumnInfo(name = "assigned_agent_id") val assignedAgentId: String? = null,
    /** The run's artifact dir (sandbox-space path of its workspace bind). */
    @ColumnInfo(name = "workspace_dir") val workspaceDir: String? = null,
    /** The worker's final answer (capped) — the board's readable result. */
    @ColumnInfo(name = "result_artifact") val resultArtifact: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Append-only status ledger — answers "why did this task get stuck". */
@Entity(
    tableName = "task_status_history",
    indices = [Index("task_id"), Index(value = ["task_id", "changed_at"])],
    foreignKeys = [
        ForeignKey(
            entity = AgentTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TaskStatusHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "from_status") val fromStatus: String,
    @ColumnInfo(name = "to_status") val toStatus: String,
    @ColumnInfo(name = "changed_by_agent_id") val changedByAgentId: String?,
    @ColumnInfo(name = "reason") val reason: String,
    @ColumnInfo(name = "changed_at") val changedAt: Long,
)

/**
 * Typed inter-agent messages. v1 records completion notices (from worker to
 * spawner); the from/to ids are session ids. message_type: question |
 * result | error | status — the spec's closed set.
 */
@Entity(
    tableName = "agent_mailbox",
    indices = [Index("to_agent_id"), Index("task_id")],
    foreignKeys = [
        ForeignKey(
            entity = AgentTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class AgentMailboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "from_agent_id") val fromAgentId: String,
    @ColumnInfo(name = "to_agent_id") val toAgentId: String,
    @ColumnInfo(name = "task_id") val taskId: String?,
    /** question | result | error | status. */
    @ColumnInfo(name = "message_type") val messageType: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Append-only mission trace — the self-improvement substrate (an agent can
 * analyse its own past runs). No FK on purpose: see AgentTaskEntity docs.
 */
@Entity(
    tableName = "mission_log",
    indices = [Index("task_id"), Index("agent_id"), Index("timestamp")],
)
data class MissionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** SPAWNED | NODE_STARTED | NODE_COMPLETED | NODE_FAILED | RUN_COMPLETED | RUN_FAILED | STATUS_CHANGED. */
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "agent_id") val agentId: String?,
    @ColumnInfo(name = "task_id") val taskId: String?,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
)
