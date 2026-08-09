package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.offload.AgentRunProgress
import com.openminis.app.ui.theme.ChatColors

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
 */
@Composable
internal fun AgentRunProgressCard(
    snapshot: AgentRunProgress.Snapshot,
    onOpenShowcase: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ChatColors.secondaryBg)
            .then(
                if (onOpenShowcase != null) Modifier.clickable(onClick = onOpenShowcase)
                else Modifier,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (snapshot.isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = ChatColors.tertiaryText,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = snapshot.graphName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            // Denominator is what has been SEEN, never the graph's node count —
            // with skipped branches the latter would stall at "2 of 7" forever.
            Text(
                text = "${snapshot.completedCount}/${snapshot.seenCount}",
                fontSize = 12.sp,
                color = ChatColors.tertiaryText,
            )
        }

        for (node in snapshot.nodes) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = glyph(node.state),
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
            }
        }

        snapshot.finalStatus?.let { status ->
            Text(
                text = status,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.tertiaryText,
            )
        }
    }
}

/**
 * Text glyphs, not icons: this card sits inside a chat transcript that is
 * selectable text, and vector icons there fight the selection container.
 */
private fun glyph(state: AgentRunProgress.NodeState): String = when (state) {
    AgentRunProgress.NodeState.RUNNING -> "▶"
    AgentRunProgress.NodeState.COMPLETED -> "✓"
    AgentRunProgress.NodeState.BLOCKED -> "■"
    AgentRunProgress.NodeState.FAILED -> "✕"
    AgentRunProgress.NodeState.SKIPPED -> "⊘"
}

@Composable
private fun stateColor(state: AgentRunProgress.NodeState) = when (state) {
    AgentRunProgress.NodeState.COMPLETED -> MaterialTheme.colorScheme.primary
    AgentRunProgress.NodeState.FAILED,
    AgentRunProgress.NodeState.BLOCKED -> MaterialTheme.colorScheme.error
    else -> ChatColors.tertiaryText
}
