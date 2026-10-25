package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.offload.AgentRunProgress
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.R

/**
 * [T-agent-graph-live-progress] Live card for a running agent graph, shown in
 * the chat that started it.
 *
 * Why: a graph run takes minutes, and the originating chat only had a blank
 * "awaiting response" bubble for the whole time — indistinguishable from a hang.
 * The per-node narration lives in the showcase session, but the user should not
 * have to open another chat to learn that anything is happening at all.
 *
 * Shows one row per agent SEEN so far. Deliberately not a fixed list of the
 * graph's nodes: a graph skips whole branches, so a pre-rendered roster would
 * show rows that never run and a progress count that never completes.
 *
 * [T-agent-panel] The pieces of this card are separate composables now: the
 * surface, the header, the node rows and the status line. The agent panel
 * shows tasks, and a task card is the same frame with a different title, a
 * different status and - when the run is still alive - the same node rows.
 * Sharing the pieces is what keeps the two cards from drifting apart; the
 * card below is a thin arrangement of them and renders exactly as it did
 * before the split.
 */
@Composable
internal fun AgentRunProgressCard(
    snapshot: AgentRunProgress.Snapshot,
    onOpenShowcase: (() -> Unit)? = null,
) {
    AgentProgressCardSurface(onClick = onOpenShowcase) {
        AgentRunProgressHeader(
            title = snapshot.graphName,
            counter = "${snapshot.completedCount}/${snapshot.seenCount}",
            isRunning = snapshot.isRunning,
        )
        // [T-agent-panel] While the run is alive, only the agents that are
        // working right now are worth a line. The ones that already
        // settled are history, and a transcript that lists them makes the
        // running one hard to find - which is the one thing the reader
        // came for. Once the run is over, every node is the result, and
        // all of them are shown.
        val visibleNodes = if (snapshot.isRunning) {
            snapshot.nodes.filter {
                it.state == AgentRunProgress.NodeState.RUNNING ||
                    it.state == AgentRunProgress.NodeState.BLOCKED
            }
        } else {
            snapshot.nodes
        }
        if (visibleNodes.isNotEmpty()) {
            AgentRunProgressNodeRows(visibleNodes)
        }
        snapshot.finalStatus?.let { status ->
            AgentRunProgressStatusText(status)
        }
    }
}

/**
 * [T-agent-panel] The card's frame: background, shape, padding and the
 * optional tap target.
 *
 * The spacing between the blocks is 6dp, and the node rows carry the same
 * spacing between themselves - so a card with N node rows has the same
 * total height as it had when every row was a direct child of the column.
 */
@Composable
internal fun AgentProgressCardSurface(
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ChatColors.secondaryBg)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

/**
 * [T-agent-panel] The card's title row: an optional spinner, the title and
 * an optional counter.
 *
 * The title and the counter are arguments rather than read from a snapshot,
 * because the two cards do not get them from the same place: the live card
 * takes the graph's name and the seen count, while the panel's task card
 * takes the task's saved title and shows the counter only while a run is
 * actually alive. A finished task has no progress to count, and a "0/0"
 * that never was true would be a lie.
 */
@Composable
internal fun AgentRunProgressHeader(
    title: String,
    counter: String?,
    isRunning: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = ChatColors.tertiaryText,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = ChatColors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (counter != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = counter,
                fontSize = 12.sp,
                color = ChatColors.tertiaryText,
            )
        }
    }
}

/**
 * [T-agent-panel] One row per agent seen so far.
 */
@Composable
internal fun AgentRunProgressNodeRows(nodes: List<AgentRunProgress.Node>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (node in nodes) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stateWord(node.state),
                    fontSize = 12.sp,
                    color = stateColor(node.state),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (node.replicaInfo != null) "${node.label} (${node.replicaInfo})"
                    else node.label,
                    fontSize = 13.sp,
                    color = if (node.state == AgentRunProgress.NodeState.RUNNING) {
                        ChatColors.primaryText
                    } else {
                        ChatColors.secondaryText
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // [A3] Everything that explains a long wait, only while running.
                // A settled row keeps just its name: the attempt count and tool
                // of a finished node are noise, and a stale tool name would claim
                // work that is not happening.
                val detail = runningDetail(node)
                if (detail != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = detail,
                        fontSize = 11.sp,
                        color = ChatColors.tertiaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
    }
}

/**
 * [T-agent-panel] The status line under the rows.
 */
@Composable
internal fun AgentRunProgressStatusText(status: String) {
    Text(
        text = status,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = ChatColors.tertiaryText,
    )
}

/**
 * Trailing detail for a running node: the tool it is in, the attempt number when
 * it is past the first, and how long this attempt may take.
 *
 * Returns null for anything settled, and for a first attempt with no tool yet —
 * "· 1/1" would be noise on every row.
 *
 * Kept pure and file-private so the formatting rule is one place; the strings are
 * technical tool names the user already sees elsewhere in the transcript.
 */
private fun runningDetail(node: AgentRunProgress.Node): String? {
    if (node.state != AgentRunProgress.NodeState.RUNNING) return null
    val parts = mutableListOf<String>()
    node.tool?.let { parts.add(it) }
    // A retry is the single most confusing thing a run does silently: on run
    // f0949263 the planner restarted after a timeout and the card looked frozen.
    if (node.attempt > 1) parts.add("attempt ${node.attempt}/${node.maxAttempts}")
    if (node.timeoutMs > 0L) parts.add("up to ${node.timeoutMs / 1000}s")
    return if (parts.isEmpty()) null else "· " + parts.joinToString(" · ")
}

/**
 * [T-agent-panel] The state of a node, in words.
 *
 * This used to be a glyph - a triangle for running, a tick for done. The
 * glyph said nothing to a reader who does not know what a node is, and it
 * was the loudest thing on the card. A word says what it is, and the
 * colour still says how it went.
 *
 * Not an icon: the card sits inside a chat transcript that is selectable
 * text, and vector icons there fight the selection container.
 */
@Composable
private fun stateWord(state: AgentRunProgress.NodeState): String = when (state) {
    AgentRunProgress.NodeState.RUNNING -> stringResource(R.string.agent_node_running)
    AgentRunProgress.NodeState.COMPLETED -> stringResource(R.string.agent_node_completed)
    AgentRunProgress.NodeState.BLOCKED -> stringResource(R.string.agent_node_blocked)
    AgentRunProgress.NodeState.FAILED -> stringResource(R.string.agent_node_failed)
    AgentRunProgress.NodeState.SKIPPED -> stringResource(R.string.agent_node_skipped)
}

@Composable
private fun stateColor(state: AgentRunProgress.NodeState) = when (state) {
    AgentRunProgress.NodeState.COMPLETED -> MaterialTheme.colorScheme.primary
    AgentRunProgress.NodeState.FAILED,
    AgentRunProgress.NodeState.BLOCKED -> MaterialTheme.colorScheme.error
    else -> ChatColors.tertiaryText
}
