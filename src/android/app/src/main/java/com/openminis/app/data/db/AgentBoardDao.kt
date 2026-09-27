package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * [T-task-board] DAO for the agent task board. CRUD stays trivial (compile-
 * checked by Room); every decision with a rule in it lives in
 * AgentBoardLogic so it is unit-testable without a device.
 *
 * Status transitions go through [transition] ONLY: the task row update and
 * the append-only history row are one transaction — a task that moved
 * without a ledger entry is a board bug, and a ledger entry without a task
 * update is a race.
 */
@Dao
interface AgentBoardDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: AgentTaskEntity)

    @Query("UPDATE agent_tasks SET status = :to, updated_at = :now WHERE id = :taskId")
    suspend fun setStatus(taskId: String, to: String, now: Long)

    @Query("UPDATE agent_tasks SET assigned_agent_id = :agentId, result_artifact = :result, updated_at = :now WHERE id = :taskId")
    suspend fun setResult(taskId: String, agentId: String?, result: String?, now: Long)

    @Insert
    suspend fun insertHistory(entry: TaskStatusHistoryEntity)

    @Transaction
    suspend fun transition(
        taskId: String,
        from: String,
        to: String,
        byAgentId: String?,
        reason: String,
        now: Long,
    ) {
        setStatus(taskId, to, now)
        insertHistory(
            TaskStatusHistoryEntity(
                taskId = taskId,
                fromStatus = from,
                toStatus = to,
                changedByAgentId = byAgentId,
                reason = reason,
                changedAt = now,
            ),
        )
    }

    @Insert
    suspend fun insertMailbox(message: AgentMailboxEntity)

    @Insert
    suspend fun insertMissionEvent(event: MissionLogEntity)

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun taskById(id: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE team_id = :teamId ORDER BY created_at DESC LIMIT :limit")
    suspend fun tasksForTeam(teamId: String, limit: Int = 50): List<AgentTaskEntity>

    @Query("SELECT * FROM task_status_history WHERE task_id = :taskId ORDER BY changed_at ASC")
    suspend fun historyForTask(taskId: String): List<TaskStatusHistoryEntity>

    @Query("SELECT * FROM agent_mailbox WHERE to_agent_id = :agentId ORDER BY created_at DESC LIMIT :limit")
    suspend fun mailboxFor(agentId: String, limit: Int = 50): List<AgentMailboxEntity>

    @Query("SELECT * FROM mission_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentMissionEvents(limit: Int = 100): List<MissionLogEntity>

    /** Board maintenance: drop closed tasks (and, by cascade, their history). */
    @Query("DELETE FROM agent_tasks WHERE updated_at < :cutoff AND status IN ('COMPLETED', 'FAILED')")
    suspend fun pruneClosedBefore(cutoff: Long): Int

    @Query("DELETE FROM mission_log WHERE timestamp < :cutoff")
    suspend fun pruneMissionBefore(cutoff: Long): Int
}
