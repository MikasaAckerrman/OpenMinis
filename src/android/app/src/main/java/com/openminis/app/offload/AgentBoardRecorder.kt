package com.openminis.app.offload

import android.content.Context
import com.openminis.app.data.db.AgentMailboxEntity
import com.openminis.app.data.db.AgentTaskEntity
import com.openminis.app.data.db.MissionLogEntity
import com.openminis.app.data.db.ProviderDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    private const val PRUNE_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val PRUNE_AGE_MS = 30L * 24 * 60 * 60 * 1000

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
    ): Unit = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val dao = ProviderDatabase.getInstance(context).agentBoardDao()
            dao.upsertTask(
                AgentTaskEntity(
                    id = taskId,
                    teamId = teamId,
                    // [event-matrix L1/H8 fix] Caps on the stored text: a
                    // model can emit a 100K-char task into one spawn, and
                    // title/description were stored WHOLESALE — unbounded
                    // rows in a phone DB. The description keeps enough to
                    // re-delegate (4K); the title is a label (120), and
                    // teamSummary already truncates it to 60 for display.
                    title = title.take(120),
                    description = description.take(4000),
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
        cancelled: Boolean = false,
        // [T-board-cancelled-status] The terminal write must survive the
        // cancellation that caused it.
        //
        // This function is called from the catch block of a cancelled
        // coroutine, and withContext checks the caller's context before it
        // runs the block: in a cancelled coroutine it throws immediately and
        // the block never executes. The board would then keep the task as
        // RUNNING for good - exactly the zombie the CANCELLED status exists
        // to prevent.
        //
        // NonCancellable makes this one write uninterruptible. The
        // cancellation itself is still rethrown by the caller afterwards;
        // only the record of what happened survives it.
    ): Unit = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val dao = ProviderDatabase.getInstance(context).agentBoardDao()
            // [T-board-terminal-outcome] One decision, four consequences.
            val outcome = AgentBoardLogic.terminalOutcome(succeeded, cancelled)
            val to = outcome.status
            // [T-board-atomic-finish] One transaction: the status move, the
            // ledger row, the stored result, the letter to the spawning
            // session and the mission event either all happen or none do.
            dao.finishTask(
                taskId = taskId,
                from = AgentBoardLogic.STATUS_RUNNING,
                to = to,
                byAgentId = assignedAgentId,
                reason = outcome.reason,
                result = result.take(RESULT_CAP),
                toAgentId = teamId,
                messageType = outcome.messageType,
                eventType = outcome.eventType,
                eventPayload = if (succeeded) "ok" else result.take(200),
                now = now,
            )
            // [T-task-board] Board maintenance rides on the terminal write —
            // no separate lifecycle, no new call sites to forget.
            maybePrune(context)
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

    /**
     * [T-checkpoint] Generic append-only event (CHECKPOINTED and future node-
     * level trace points). Best-effort like every recorder call.
     */
    suspend fun missionEvent(
        context: Context,
        eventType: String,
        taskId: String,
        agentId: String?,
        payload: String,
    ): Unit = withContext(Dispatchers.IO) {
        runCatching {
            ProviderDatabase.getInstance(context).agentBoardDao()
                .insertMissionEvent(
                    MissionLogEntity(
                        eventType = eventType,
                        agentId = agentId,
                        taskId = taskId,
                        payload = payload,
                        timestamp = System.currentTimeMillis(),
                    ),
                )
        }.onFailure {
            com.openminis.app.logging.AppLogger.warning(
                "AgentBoard",
                "missionEvent '$eventType' write failed for $taskId (run continues): ${it.message}",
            )
        }
    }

    /**
     * [T-task-board] Deep-analysis fix: pruneClosedBefore had NO caller —
     * the board grew forever on a phone. Called opportunistically from
     * taskFinished, at most once per [PRUNE_INTERVAL_MS]: closed tasks
     * (COMPLETED *and* FAILED — a 30-day-old failure is history, not a
     * re-delegation signal; any team's working memory is far shorter) and
     * mission events older than [PRUNE_AGE_MS] (30 days) go.
     */
    private val lastPrune = java.util.concurrent.atomic.AtomicLong(0)

    private suspend fun maybePrune(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastPrune.get() < PRUNE_INTERVAL_MS) return
        if (!lastPrune.compareAndSet(lastPrune.get(), now)) return
        runCatching {
            val dao = ProviderDatabase.getInstance(context).agentBoardDao()
            dao.pruneMissionBefore(now - PRUNE_AGE_MS)
            dao.pruneMailboxBefore(now - PRUNE_AGE_MS)
            dao.pruneClosedBefore(now - PRUNE_AGE_MS)
        }.onFailure {
            com.openminis.app.logging.AppLogger.warning(
                "AgentBoard", "board prune failed (non-fatal): ${it.message}",
            )
        }
    }
}
