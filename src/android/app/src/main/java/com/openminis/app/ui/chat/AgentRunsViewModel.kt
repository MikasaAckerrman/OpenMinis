package com.openminis.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.db.AgentBoardDao
import com.openminis.app.data.db.AgentTaskEntity
import com.openminis.app.data.db.ChatDao
import com.openminis.app.offload.AgentBoardLogic
import com.openminis.app.offload.AgentRunProgress
import com.openminis.app.offload.SubagentExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * [T-agent-panel] One task as the panel shows it.
 *
 * The row keeps the task as it was saved and adds what only the panel can
 * know: the live progress, whether the task outlived its process, where its
 * result is and whether it can be stopped.
 */
data class AgentTaskRow(
    val task: AgentTaskEntity,
    /** The status the panel shows, already resolved - see [AgentRunsViewModel]. */
    val status: String,
    /** The live progress, or null when there is none any more. */
    val live: AgentRunProgress.Snapshot?,
    /** The task's process is gone: the run died with it. */
    val stale: Boolean,
    /** The session that holds the result, when the run made one. */
    val resultSessionId: String?,
    /** This particular run can really be stopped. */
    val cancellable: Boolean,
)

/**
 * [T-agent-panel] What the panel shows.
 */
data class AgentRunsUiState(
    val active: List<AgentTaskRow> = emptyList(),
    val finished: List<AgentTaskRow> = emptyList(),
) {
    /** The number next to the entry point: the tasks that are in flight. */
    val activeCount: Int get() = active.size

    /**
     * Whether the entry point is worth showing at all.
     *
     * Active OR recent: a task that finished a minute ago is still the thing
     * the user wants to open, and the entry point must not disappear the
     * moment the count of running tasks reaches zero - that is exactly when
     * the user wants to look at the result.
     */
    val hasSomethingToShow: Boolean get() = active.isNotEmpty() || finished.isNotEmpty()
}

/**
 * [T-agent-panel] The status the panel shows for a task.
 *
 * A pure rule, so it can be tested on its own: the panel's correctness
 * rests on it, and a rule that only exists inside a combine block is a
 * rule nobody can check.
 *
 * Two sides, one idea:
 *
 *  - a TERMINAL status in the database wins over anything the progress
 *    says. The board is the durable record of how the run ended, and a
 *    snapshot that still says "running" is a leftover.
 *  - a terminal status in the progress wins over a NON-terminal status in
 *    the database. The run says it finished; the board just did not hear
 *    about it, because the write was lost. The run's own testimony is
 *    newer than the row.
 *
 * So a terminal status always wins, and only a conflict between two
 * terminal statuses is settled in favour of the database.
 *
 * The second side is only safe because a finished run does not leave a
 * snapshot behind: an ephemeral run is cleared right after it is marked
 * settled, and a task id belongs to one run. A stale snapshot of an
 * earlier run therefore cannot arrive here for a new one.
 */
internal fun resolveAgentTaskStatus(
    dbStatus: String,
    snapshotFinalStatus: String?,
): String = when {
    dbStatus in AGENT_TASK_TERMINAL_STATUSES -> dbStatus
    snapshotFinalStatus != null -> snapshotFinalStatus
    else -> dbStatus
}

/**
 * [T-agent-panel] The statuses a task does not come back from.
 */
internal val AGENT_TASK_TERMINAL_STATUSES = setOf(
    AgentBoardLogic.STATUS_COMPLETED,
    AgentBoardLogic.STATUS_FAILED,
    AgentBoardLogic.STATUS_CANCELLED,
)

/**
 * [T-agent-panel] The state of the agent panel.
 *
 * It combines three sources, and they do not agree with each other by
 * nature:
 *
 *  - the BOARD, in the database, is durable. It says what a task is and how
 *    it ended.
 *  - the PROGRESS, in memory, says what is happening right now. It is gone
 *    when the run ends, and gone when the process dies.
 *  - the REGISTRY of running background runs says what can still be stopped.
 *
 * The panel is a live screen, so it must not wait to be asked: a task that
 * starts, ends or is cancelled while the panel is open has to appear and
 * change by itself. The board is read as an observable query and the
 * progress is a state flow, and the two are combined here.
 */
class AgentRunsViewModel(
    private val teamId: String,
    private val boardDao: AgentBoardDao,
    private val chatDao: ChatDao,
) : ViewModel() {

    /**
     * The finished tasks together with the sessions that hold their results.
     *
     * The lookup is a batch: twenty finished tasks on one screen would
     * otherwise be twenty queries. The mapping is suspend, so it happens in
     * the flow, not in the combine.
     */
    private val finishedWithShowcase: Flow<Pair<List<AgentTaskEntity>, Map<String, String>>> =
        boardDao.recentFinishedTasksForTeam(teamId, FINISHED_LIMIT)
            .map { tasks ->
                tasks to if (tasks.isEmpty()) {
                    emptyMap()
                } else {
                    chatDao.showcaseSessionsForRuns(tasks.map { it.id })
                        .mapNotNull { row -> row.runId?.let { it to row.sessionId } }
                        .toMap()
                }
            }

    /**
     * The panel's state.
     *
     * It stops collecting a moment after the last subscriber goes away and
     * starts again when one comes back: a panel nobody is looking at should
     * not keep a database query and a flow alive.
     */
    val uiState: StateFlow<AgentRunsUiState> = combine(
        boardDao.activeTasksForTeam(teamId),
        finishedWithShowcase,
        AgentRunProgress.snapshots,
    ) { activeTasks, (finishedTasks, showcases), liveMap ->
        buildState(activeTasks, finishedTasks, showcases, liveMap)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = AgentRunsUiState(),
    )

    /**
     * The panel's state, built from the three sources.
     *
     * The status is resolved here and not in the card, because the card is
     * presentational and this is where the rules live.
     *
     * Two rules, and they are the same rule seen from two sides:
     *
     *  - a TERMINAL status in the database wins over anything the progress
     *    says. The board is the durable record of how the run ended, and a
     *    snapshot that still says "running" is a leftover.
     *  - a terminal status in the progress wins over a NON-terminal status
     *    in the database. The run says it finished; the board just did not
     *    hear about it, because the write was lost. The run's own testimony
     *    is newer than the row.
     *
     * So a terminal status always wins, and only a conflict between two
     * terminal statuses is settled in favour of the database.
     */
    private fun buildState(
        activeTasks: List<AgentTaskEntity>,
        finishedTasks: List<AgentTaskEntity>,
        showcases: Map<String, String>,
        liveMap: Map<String, AgentRunProgress.Snapshot>,
    ): AgentRunsUiState {
        val now = System.currentTimeMillis()
        val active = mutableListOf<AgentTaskRow>()
        val finished = mutableListOf<AgentTaskRow>()

        for (task in activeTasks) {
            val live = liveMap[task.id]
            val status = resolveAgentTaskStatus(
                dbStatus = task.status,
                snapshotFinalStatus = live?.finalStatus,
            )
            val row = buildRow(task, status, live, showcases, now)
            if (task.status in TERMINAL_STATUSES || live?.finalStatus != null) {
                finished += row
            } else {
                active += row
            }
        }

        for (task in finishedTasks) {
            finished += buildRow(task, task.status, liveMap[task.id], showcases, now)
        }

        return AgentRunsUiState(active = active, finished = finished)
    }

    private fun buildRow(
        task: AgentTaskEntity,
        status: String,
        live: AgentRunProgress.Snapshot?,
        showcases: Map<String, String>,
        now: Long,
    ): AgentTaskRow = AgentTaskRow(
        task = task,
        status = status,
        live = live,
        // The rule that knows about the process: a row written before this
        // process started belongs to a process that is gone, and a run that
        // is still visible in this process is alive whatever its row says.
        stale = AgentBoardLogic.isStale(
            status = task.status,
            updatedAt = task.updatedAt,
            nowMs = now,
            hasLiveProgress = live != null,
        ),
        resultSessionId = showcases[task.id],
        // The confirmation, not the status: a task that says RUNNING may be
        // a run that cannot be stopped at all, and a button that does
        // nothing is worse than no button.
        cancellable = status == AgentBoardLogic.STATUS_RUNNING &&
            SubagentExecutor.isCancellable(task.id),
    )

    companion object {
        /** How many finished tasks the panel shows. */
        const val FINISHED_LIMIT = 20

        /** How long the panel keeps collecting after the last subscriber leaves. */
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

        /** The statuses a task does not come back from. */
        val TERMINAL_STATUSES: Set<String> get() = AGENT_TASK_TERMINAL_STATUSES
    }
}
