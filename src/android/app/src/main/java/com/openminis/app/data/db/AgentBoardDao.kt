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

    /**
     * [T-board-guarded-transition] The status move is CONDITIONAL on the
     * status the caller believes the task is in.
     *
     * The unguarded form ("WHERE id = :taskId") let two concurrent
     * transitions both apply: the task could be recorded as RUNNING ->
     * COMPLETED and then RUNNING -> FAILED, a history that cannot happen.
     * The ledger was faithful to what the database did, but the database did
     * something impossible.
     *
     * Returns the number of rows changed, so the caller can tell whether the
     * move actually happened. A transition whose precondition does not hold
     * is a no-op, not a silent overwrite.
     */
    @Query("UPDATE agent_tasks SET status = :to, updated_at = :now WHERE id = :taskId AND status = :from")
    suspend fun setStatusIf(taskId: String, from: String, to: String, now: Long): Int

    @Query("UPDATE agent_tasks SET assigned_agent_id = :agentId, result_artifact = :result, updated_at = :now WHERE id = :taskId")
    suspend fun setResult(taskId: String, agentId: String?, result: String?, now: Long)

    @Insert
    suspend fun insertHistory(entry: TaskStatusHistoryEntity)

    /**
     * [T-board-guarded-transition] Moves a task from one status to another,
     * recording the move in the ledger.
     *
     * The status update and the history row are one transaction: a task that
     * moved without a ledger entry is a board bug, and a ledger entry
     * without a task update is a race.
     *
     * The move is also conditional on the task actually being in [from]. If
     * it is not - another transition already moved it, or the task was
     * never started - nothing happens and no ledger row is written. The
     * ledger therefore stays a true history instead of recording transitions
     * that never happened.
     */
    @Transaction
    suspend fun transition(
        taskId: String,
        from: String,
        to: String,
        byAgentId: String?,
        reason: String,
        now: Long,
    ) {
        val changed = setStatusIf(taskId, from, to, now)
        if (changed == 0) return
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

    /**
     * [T-task-board] Statuses of SPECIFIC task ids — the dependency gate's
     * read. Deep-analysis fix: the gate previously read the newest-100 team
     * window; a depends_on citing an OLDER completed task fell out of the
     * window and was refused as 'unknown/pruned'. An id-targeted read has no
     * window to fall out of.
     */
    @Query("SELECT * FROM agent_tasks WHERE id IN (:ids)")
    suspend fun tasksByIds(ids: List<String>): List<AgentTaskEntity>

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

    /**
     * [T-task-board] Mailbox rows survive their task's pruning by design
     * (task_id SET NULL — the notice outlives the card), so without this
     * the table grows forever. Deep-analysis fix: same 30-day horizon as
     * the rest of the board.
     */
    @Query("DELETE FROM agent_mailbox WHERE created_at < :cutoff")
    suspend fun pruneMailboxBefore(cutoff: Long): Int
}
