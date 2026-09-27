package com.openminis.app.offload

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.db.AgentMailboxEntity
import com.openminis.app.data.db.AgentTaskEntity
import com.openminis.app.data.db.MissionLogEntity
import com.openminis.app.data.db.ProviderDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-task-board] The write path from the subagent executor to the durable
 * board. Kept as a separate object (not inline in SubagentExecutor) because
 * every call is fire-and-forget-by-policy: a board write that throws must
 * NEVER take the run's result down with it — the run already produced its
 * answer; the ledger is bookkeeping. So every entry point catches its own
 * errors and logs them, and the executor calls these best-effort.
 *
 * All writes are IO-dispatched and suspend; the executor is already on IO
 * at its call sites.
 */
internal object AgentBoardRecorder {

    private const val RESULT_CAP = 4000

    /** Task row + SPAWNED mission event + PENDING→RUNNING transition. */
    suspend fun taskStarted(
        context: Context,
        taskId: String,
        teamId: String,
        roleRequired: String,
        title: String,
        description: String,
        workspaceDir: String?,
        dependsOnJson: String = "[]",
    ): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val dao = ProviderDatabase.getInstance(context).agentBoardDao()
            dao.upsertTask(
                AgentTaskEntity(
                    id = taskId,
                    teamId = teamId,
                    title = title,
                    description = description,
                    roleRequired = roleRequired,
                    status = AgentBoardLogic.STATUS_RUNNING,
                    dependsOnTaskIds = dependsOnJson,
                    workspaceDir = workspaceDir,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            dao.insertMissionEvent(
                MissionLogEntity(
                    eventType = "SPAWNED",
                    agentId = teamId,
                    taskId = taskId,
                    payload = "role=$roleRequired title=${title.take(80)}",
                    timestamp = now,
                ),
            )
        }.onFailure {
            com.openminis.app.logging.AppLogger.warning(
                "AgentBoard",
                "taskStarted ledger write failed for $taskId (run continues): ${it.message}",
            )
        }
    }

    /**
     * Terminal transition + result + mailbox notice to the spawner +
     * mission event. assignedAgentId: the worker session bound to the run.
     */
    suspend fun taskFinished(
        context: Context,
        taskId: String,
        teamId: String,
        assignedAgentId: String?,
        succeeded: Boolean,
        result: String,
    ): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val dao = ProviderDatabase.getInstance(context).agentBoardDao()
            val to = if (succeeded) AgentBoardLogic.STATUS_COMPLETED else AgentBoardLogic.STATUS_FAILED
            dao.transition(
                taskId = taskId,
                from = AgentBoardLogic.STATUS_RUNNING,
                to = to,
                byAgentId = assignedAgentId,
                reason = if (succeeded) "completed" else "failed",
                now = now,
            )
            dao.setResult(taskId, assignedAgentId, result.take(RESULT_CAP), now)
            dao.insertMailbox(
                AgentMailboxEntity(
                    fromAgentId = assignedAgentId ?: taskId,
                    toAgentId = teamId,
                    taskId = taskId,
                    messageType = if (succeeded) "result" else "error",
                    body = result.take(RESULT_CAP),
                    createdAt = now,
                ),
            )
            dao.insertMissionEvent(
                MissionLogEntity(
                    eventType = if (succeeded) "RUN_COMPLETED" else "RUN_FAILED",
                    agentId = assignedAgentId,
                    taskId = taskId,
                    payload = if (succeeded) "ok" else result.take(200),
                    timestamp = now,
                ),
            )
        }.onFailure {
            com.openminis.app.logging.AppLogger.warning(
                "AgentBoard",
                "taskFinished ledger write failed for $taskId (run continues): ${it.message}",
            )
        }
    }

    /** Cross-turn team summary for the next spawn's result header. Null = no history. */
    suspend fun teamSummary(context: Context, teamId: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val tasks = ProviderDatabase.getInstance(context)
                    .agentBoardDao()
                    .tasksForTeam(teamId, limit = 20)
                if (tasks.isEmpty()) null else AgentBoardLogic.teamSummary(tasks)
            }.getOrNull()
        }
}
