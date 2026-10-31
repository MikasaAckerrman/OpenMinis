package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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
    @Query(SQL_SET_STATUS_IF)
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
    /**
     * [T-board-atomic-finish] The whole terminal write, as one transaction.
     *
     * The status move and its consequences were four separate DAO calls,
     * each its own transaction. A crash between them left a task marked
     * COMPLETED with no result stored and no letter delivered to the
     * session that spawned it - the board said the run finished and nobody
     * could tell what it produced.
     *
     * The move is still conditional on the task being in [from]. If it is
     * not - a second completion for the same task - nothing happens at all:
     * no status change, no history row, no second result, and no second
     * letter in the mailbox. The spawning session receives one result, not
     * two, and the result it receives is the one that completed the task.
     */
    @Transaction
    suspend fun finishTask(
        taskId: String,
        from: String,
        to: String,
        byAgentId: String?,
        reason: String,
        result: String,
        toAgentId: String,
        messageType: String,
        eventType: String,
        eventPayload: String,
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
        setResult(taskId, byAgentId, result, now)
        insertMailbox(
            AgentMailboxEntity(
                fromAgentId = byAgentId ?: taskId,
                toAgentId = toAgentId,
                taskId = taskId,
                messageType = messageType,
                body = result,
                createdAt = now,
            ),
        )
        insertMissionEvent(
            MissionLogEntity(
                eventType = eventType,
                agentId = byAgentId,
                taskId = taskId,
                payload = eventPayload,
                timestamp = now,
            ),
        )
    }

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

    /**
     * [T-board-orphan-sweep] Rows left in a non-terminal state by a
     * process that is gone.
     *
     * Such a row is not a run: nothing is running it. The only writer
     * that could have closed it died with its process, and the prune
     * does not reach it - it removes rows that are already closed. So
     * the row would read "running" for good, and every count that
     * includes it would be wrong.
     */
    @Query(SQL_ORPHANED_BEFORE)
    suspend fun orphanedBefore(cutoff: Long): List<AgentTaskEntity>

    /**
     * [T-board-orphan-sweep] Close the rows a dead process left behind.
     *
     * The task is cancelled, not completed: it was not finished, and
     * saying so is the honest reading. The history gets the reason, so
     * the board says why the row closed rather than just that it did.
     */
    @Transaction
    suspend fun cancelOrphansBefore(cutoff: Long, now: Long): Int {
        val orphans = orphanedBefore(cutoff)
        var closed = 0
        for (row in orphans) {
            if (closeAsCancelled(row, now, "the process that owned this run is gone")) closed++
        }
        return closed
    }

    /**
     * [T-board-orphan-sweep] Close the given rows as cancelled.
     *
     * The startup sweep uses the process start as a cutoff and can only
     * see rows from a process that is gone. This one closes rows the
     * caller has already decided are orphaned - a row whose executor died
     * inside the running process.
     */
    @Transaction
    suspend fun cancelOrphans(
        rows: List<AgentTaskEntity>,
        now: Long,
        reason: String,
        isStillOrphan: (AgentTaskEntity) -> Boolean,
    ): Int {
        var closed = 0
        for (row in rows) {
            // The rows were read a moment ago. The status is re-checked by
            // the compare and set below, but the orphan condition is not
            // part of that compare and set: a task that became live again
            // in between would be closed on a stale reading. So the
            // condition is asked again here, right before the write.
            if (!isStillOrphan(row)) continue
            if (closeAsCancelled(row, now, reason)) closed++
        }
        return closed
    }

    /**
     * [T-board-orphan-sweep] Rows that are still active.
     */
    @Query("SELECT * FROM agent_tasks WHERE status IN ('PENDING','RUNNING','BLOCKED')")
    suspend fun activeTasks(): List<AgentTaskEntity>

    /**
     * [T-agent-board-rpc] Live rows of ONE team, newest first. Filtered in
     * SQL, not in memory, so a team with a long recent finished history
     * cannot crowd its RUNNING tasks out of the LIMIT - the debug.agentBoard
     * RPC reads this for its live per-team view.
     */
    @Query(
        "SELECT * FROM agent_tasks WHERE team_id = :teamId " +
            "AND status IN ('PENDING','RUNNING','BLOCKED') " +
            "ORDER BY created_at DESC LIMIT :limit"
    )
    suspend fun activeTasksForTeam(teamId: String, limit: Int = 50): List<AgentTaskEntity>

    /**
     * Close one row as cancelled, keeping the history entry. Returns true
     * when this call is the one that changed the status - the compare and
     * set makes a second caller a no-op instead of a double history row.
     */
    private suspend fun closeAsCancelled(row: AgentTaskEntity, now: Long, reason: String): Boolean {
        val changed = setStatusIf(row.id, row.status, "CANCELLED", now)
        if (changed == 0) return false
        insertHistory(
            TaskStatusHistoryEntity(
                taskId = row.id,
                fromStatus = row.status,
                toStatus = "CANCELLED",
                changedByAgentId = null,
                reason = reason,
                changedAt = now,
            ),
        )
        return true
    }

    @Insert
    suspend fun insertMailbox(message: AgentMailboxEntity)

    @Insert
    suspend fun insertMissionEvent(event: MissionLogEntity)

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun taskById(id: String): AgentTaskEntity?

    /**
     * [T-agent-await-result] Team-scoped single-task read for await_result.
     *
     * Possession of a spawn id does NOT authorize reading another team's
     * task: the id can reach a session from arbitrary text (a user message,
     * pasted content), so the read is scoped exactly like task_board
     * (tasksForTeam). A fork reads its own team only — the same visibility
     * its task_board has. Returns null for both "unknown id" and "foreign
     * team", so existence in another team is not even observable.
     */
    @Query(SQL_TASK_BY_ID_FOR_TEAM)
    suspend fun taskByIdForTeam(id: String, teamId: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE team_id = :teamId ORDER BY created_at DESC LIMIT :limit")
    suspend fun tasksForTeam(teamId: String, limit: Int = 50): List<AgentTaskEntity>

    /**
     * [T-board-panel] The tasks of a team that are still in flight.
     *
     * PENDING, RUNNING and BLOCKED. BLOCKED belongs here and not with the
     * finished ones: a blocked task has not ended, it is waiting for its
     * dependencies and it can still proceed.
     *
     * Ordered oldest first, so the task that has been waiting longest is
     * the one the user sees at the top - that is the one that needs
     * attention.
     *
     * There is no window here on purpose. [tasksForTeam] needs a limit
     * because it serves a mixed list, where fifty finished tasks would
     * push the live ones out; this query serves only the live ones, and
     * their number is small by nature. A limit would be an arbitrary
     * number that could hide a task.
     *
     * [T-board-panel] A Flow, not a one-shot list. The panel has to show
     * what is happening now: a task that starts, finishes or is
     * cancelled while the sheet is open has to appear and change by
     * itself. A list would have to be fetched again by hand, and the
     * panel would show a stale board - the user would press "stop" on a
     * run that had already ended, or would not see a run that had just
     * started.
     */
    @Query(SQL_ACTIVE_TASKS)
    fun activeTasksForTeam(teamId: String): Flow<List<AgentTaskEntity>>

    /**
     * [T-board-panel] The finished tasks of a team, newest first.
     *
     * Every terminal state: COMPLETED, FAILED and CANCELLED. A cancelled
     * task is finished, and the panel has to be able to say so - hiding it
     * would make a cancelled run look like one that never happened.
     *
     * Ordered by when the task was last touched, so the run the user just
     * stopped is the first one it sees.
     *
     * [T-board-panel] A Flow, like [activeTasksForTeam]: a task that ends
     * while the panel is open has to move from the live list to the
     * finished one on its own.
     */
    @Query(SQL_RECENT_FINISHED)
    fun recentFinishedTasksForTeam(
        teamId: String,
        limit: Int = 20,
    ): Flow<List<AgentTaskEntity>>

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

    /**
     * Board maintenance: drop closed tasks (and, by cascade, their history).
     *
     * [T-board-panel] CANCELLED is closed too. The name and the comment said
     * "closed tasks" while the query covered only COMPLETED and FAILED, so
     * every cancelled task stayed in the table forever: the board grew
     * without bound and the finished list filled up with cancellations.
     *
     * BLOCKED and RUNNING are deliberately not here - they are not closed.
     * A task that is still waiting for its dependencies must survive the
     * prune; a running one must survive it even more so.
     */
    @Transaction
    suspend fun pruneClosedBefore(cutoff: Long): Int {
        // [T-board-prune-history] The history rows go FIRST, while the
        // tasks are still there to be matched against.
        //
        // The history table DOES have a foreign key with ON DELETE
        // CASCADE, so the delete below would take the history with it -
        // but only while foreign key enforcement is on. Doing it
        // explicitly does not depend on that, and the subquery is the
        // same predicate the delete uses, so the history of exactly the
        // deleted tasks goes with them and the two cannot disagree.
        deleteHistoryForClosedBefore(cutoff)
        return deleteClosedBefore(cutoff)
    }

    /**
     * [T-board-prune-history] The history of the tasks that are about to
     * be pruned. Uses a subquery rather than a second pass, so the two
     * statements cannot disagree about which tasks those are.
     */
    @Query(SQL_PRUNE_HISTORY)
    suspend fun deleteHistoryForClosedBefore(cutoff: Long): Int

    @Query(SQL_PRUNE_CLOSED)
    suspend fun deleteClosedBefore(cutoff: Long): Int

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

    companion object {
        /**
         * [T-board-guarded-transition] The status move, conditional on the
         * status the task is actually in.
         *
         * This is a constant rather than a string inside the annotation so
         * that the test can run the very query the app runs, instead of a
         * copy of it that can drift away from the real thing.
         */
        const val SQL_SET_STATUS_IF =
            "UPDATE agent_tasks SET status = :to, updated_at = :now " +
                "WHERE id = :taskId AND status = :from"

        /**
         * [T-board-panel] The queries the agent panel reads.
         *
         * Constants rather than strings inside the annotations, so the
         * tests run the very queries the app runs instead of copies that
         * can drift away from the real thing.
         */
        const val SQL_ACTIVE_TASKS =
            "SELECT * FROM agent_tasks WHERE team_id = :teamId " +
                "AND status IN ('PENDING', 'RUNNING', 'BLOCKED') " +
                "ORDER BY created_at ASC"

        const val SQL_RECENT_FINISHED =
            "SELECT * FROM agent_tasks WHERE team_id = :teamId " +
                "AND status IN ('COMPLETED', 'FAILED', 'CANCELLED') " +
                "ORDER BY updated_at DESC LIMIT :limit"

        /**
         * [T-board-orphan-sweep] The rows a dead process left behind:
         * open, and untouched since before the process that owned them
         * is gone.
         *
         * A constant, like the other board queries, so the test can run
         * this exact string on a real SQLite engine - the app and the
         * test cannot drift apart.
         */
        const val SQL_ORPHANED_BEFORE =
            "SELECT * FROM agent_tasks WHERE status IN ('PENDING', 'RUNNING', 'BLOCKED') " +
                "AND updated_at < :cutoff"

        const val SQL_PRUNE_CLOSED =
            "DELETE FROM agent_tasks WHERE updated_at < :cutoff " +
                "AND status IN ('COMPLETED', 'FAILED', 'CANCELLED')"

        /**
         * [T-board-prune-history] The history of exactly the tasks the
         * prune removes - the same predicate, expressed as a subquery over
         * the tasks that still exist.
         */
        const val SQL_PRUNE_HISTORY =
            "DELETE FROM task_status_history WHERE task_id IN (" +
                "SELECT id FROM agent_tasks WHERE updated_at < :cutoff " +
                "AND status IN ('COMPLETED', 'FAILED', 'CANCELLED'))"

        /**
         * [T-agent-await-result] The await_result read: same-team only.
         * A constant, like the other board queries, so the test runs the
         * very query the app runs instead of a copy of it.
         */
        const val SQL_TASK_BY_ID_FOR_TEAM =
            "SELECT * FROM agent_tasks WHERE id = :id AND team_id = :teamId"
    }
}
