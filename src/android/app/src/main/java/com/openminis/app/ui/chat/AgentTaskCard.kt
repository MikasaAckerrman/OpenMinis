package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.openminis.app.data.db.AgentTaskEntity
import com.openminis.app.offload.AgentBoardLogic
import com.openminis.app.offload.AgentRunProgress
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-agent-panel] A task of the agent board.
 *
 * This is a presentational component and nothing else. It does not read the
 * database, does not look at the clock and does not decide what can be
 * cancelled - the caller tells it all three. That is what keeps the panel's
 * rules in one place instead of spread over the cards, and it is what lets
 * the same card be shown in a panel, in a test and in a screenshot without
 * three different answers.
 *
 * The data comes from two places, and they do not say the same thing:
 *
 *  - the TASK carries what was saved: the title, the status, the result.
 *    It is durable - it survives the process.
 *  - the LIVE snapshot carries what is happening right now: the nodes, how
 *    many have been seen, whether the run is still going. It is in memory
 *    only, and it is gone once the run ends.
 *
 * The task is the truth about the outcome, the snapshot is the truth about
 * the progress. A finished task has no progress, so it gets no counter: a
 * "0/0" would be a number that was never true.
 */
@Composable
internal fun AgentTaskCard(
    task: AgentTaskEntity,
    status: String,
    live: AgentRunProgress.Snapshot?,
    stale: Boolean,
    resultSessionId: String?,
    cancellable: Boolean,
    onOpenSession: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    AgentProgressCardSurface(onClick = null) {
        AgentRunProgressHeader(
            title = task.title.ifBlank { task.id },
            // The counter is a fact about a run in progress. Without a live
            // snapshot there is nothing to count.
            counter = live?.let { "${it.completedCount}/${it.seenCount}" },
            // The spinner follows the live run, not the stored status: a task
            // that was running when the process died has a stored status of
            // RUNNING and no run at all, and a spinner there would be a lie.
            isRunning = live?.isRunning == true,
        )
        if (live != null && live.nodes.isNotEmpty()) {
            AgentRunProgressNodeRows(live.nodes)
        }
        AgentRunProgressStatusText(taskStatusLabel(status, stale))

        val artifact = task.resultArtifact
        when {
            resultSessionId != null -> {
                // The full result lives in its own chat, where the whole
                // exchange is. Opening it is better than pasting a summary.
                Row {
                    TextButton(onClick = { onOpenSession(resultSessionId) }) {
                        Text(
                            "Показать результат",
                            fontSize = 12.sp,
                            color = ChatColors.primaryText,
                        )
                    }
                }
            }

            !artifact.isNullOrBlank() -> {
                // No session was created for this run - it produced no chat of
                // its own. The saved result is then all there is, and it is
                // self-contained: the text of the handoff, of the raw answer
                // or of the error. It can be shortened when it was written -
                // the handoff is capped - and the text says so where it was
                // cut, so what is shown here says so too.
                var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
                Text(
                    text = artifact,
                    fontSize = 12.sp,
                    color = ChatColors.secondaryText,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artifact.length > 140) {
                    Row {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(
                                if (expanded) "Свернуть" else "Показать полностью",
                                fontSize = 12.sp,
                                color = ChatColors.primaryText,
                            )
                        }
                    }
                }
            }
        }

        if (cancellable) {
            // Only shown when the caller says this particular run can really
            // be stopped. A button that silently does nothing is worse than
            // no button: the user presses it, nothing happens, and they
            // learn nothing about whether the agent is still working.
            Row {
                TextButton(onClick = { onCancel(task.id) }) {
                    Text(
                        "Остановить",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = ChatColors.primaryText,
                    )
                }
            }
        }
    }
}

/**
 * [T-agent-panel] The status line of a task.
 *
 * A task whose process is gone is not running any more, whatever its stored
 * status says - the row outlived the run. It is shown as interrupted rather
 * than as running, because a task that never finishes on its own is
 * something the user can act on.
 */
private fun taskStatusLabel(status: String, stale: Boolean): String = when {
    stale -> "Прервано"
    status == AgentBoardLogic.STATUS_RUNNING -> "Выполняется"
    status == AgentBoardLogic.STATUS_PENDING -> "В очереди"
    status == AgentBoardLogic.STATUS_BLOCKED -> "Ждёт зависимостей"
    status == AgentBoardLogic.STATUS_COMPLETED -> "Завершено"
    status == AgentBoardLogic.STATUS_FAILED -> "Ошибка"
    status == AgentBoardLogic.STATUS_CANCELLED -> "Отменено"
    else -> status
}
