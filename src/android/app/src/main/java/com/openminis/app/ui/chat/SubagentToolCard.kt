package com.openminis.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.offload.AgentRunProgress

/**
 * [B34] The inline ZCode-style card for one `spawn_subagent` call.
 *
 * The [B6] batch card (collapsed "Субагенты (X/Y завершено)" table) is gone:
 * every agent call renders HERE, in the stream, among the other tools — a
 * dark card with the role, a live status chip and the task preview, exactly
 * how ZCode shows a subagent among file_read/grep pills. Tap routes through
 * [onOpenDetail] to the hoisted ToolDetailSheet, which for AGENT_TOOL shows
 * the live per-agent preview (progress card, task, result) — the sheet
 * survives the row scrolling off-screen because it is ViewModel-owned
 * (T261), the same as every other tool's detail.
 *
 * At rest the card folds under the turn's AssistantTurnFold row like every
 * other tool — the work happened, the summary row keeps the feed compact.
 */
@Composable
internal fun SubagentToolCard(
    block: AssistantBlock,
    onOpenDetail: (String) -> Unit,
) {
    val args = remember(block.toolArgs) {
        runCatching { org.json.JSONObject(block.toolArgs) }.getOrNull()
    }
    val role = block.toolTitle.ifBlank {
        args?.optString("role").orEmpty().ifBlank { "subagent" }
    }
    val task = args?.optString("task").orEmpty()
    val chip = agentChipOf(block.toolStatus)

    Surface(
        color = Color(0xFF16191E),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262A33)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onOpenDetail(block.id) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.AccountTree,
                contentDescription = null,
                tint = Color(0xFFFF2D55),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        role,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    AgentStatusDot(chip)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        agentChipLabel(chip),
                        color = agentChipColor(chip),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (task.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        task,
                        color = Color(0xFF8B95A5),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Done-state duration — same "0.4s" style as the tool pills.
            if (block.durationMs > 0 && agentChipSettled(chip)) {
                Spacer(Modifier.width(8.dp))
                Text(
                    run {
                        val seconds = block.durationMs / 1000.0
                        if (seconds < 10) String.format("%.1fs", seconds)
                        else String.format("%.0fs", seconds)
                    },
                    color = Color(0xFF8B95A5),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** Status buckets of a subagent card — shared by the card and the detail sheet. */
internal enum class AgentChip { RUNNING, DONE, ERROR }

internal fun agentChipOf(status: ToolBlockStatus?): AgentChip = when (status) {
    ToolBlockStatus.SUCCESS -> AgentChip.DONE
    ToolBlockStatus.FAILED, ToolBlockStatus.CANCELLED, ToolBlockStatus.TIMEOUT -> AgentChip.ERROR
    else -> AgentChip.RUNNING
}

internal fun agentChipColor(chip: AgentChip): Color = when (chip) {
    AgentChip.RUNNING -> Color(0xFF32ADE6)
    AgentChip.DONE -> Color(0xFF34C759)
    AgentChip.ERROR -> Color(0xFFFF6B6B)
}

internal fun agentChipLabel(chip: AgentChip): String = when (chip) {
    AgentChip.RUNNING -> "RUNNING"
    AgentChip.DONE -> "DONE"
    AgentChip.ERROR -> "ERROR"
}

internal fun agentChipSettled(chip: AgentChip): Boolean = chip != AgentChip.RUNNING

/**
 * [B34] The live status dot. RUNNING pulses (the spec's "пульсирующий
 * циан") so a minutes-long agent reads as ALIVE from the feed — the same
 * distinction AgentRunProgress was built for ("working" vs "stuck").
 */
@Composable
internal fun AgentStatusDot(chip: AgentChip, size: Int = 7) {
    val alpha = if (chip == AgentChip.RUNNING) {
        val transition = rememberInfiniteTransition(label = "agentChipPulse")
        val pulse by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "agentChipPulseAlpha",
        )
        pulse
    } else 1f
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(agentChipColor(chip).copy(alpha = alpha), CircleShape),
    )
}

/**
 * [B34-UI] The per-agent SESSION view inside ToolDetailSheet — the
 * "мини-окно" structured like the chat itself:
 *
 *  - header: role icon + role badge + live status chip + timer;
 *  - the TASK as a user-message bubble (what the orchestrator asked for);
 *  - the agent's own tool calls, LIVE from AgentRunProgress (label, the
 *    tool in flight, attempt counter) while the run is alive;
 *  - the RESULT as an assistant card once the agent settles.
 *
 * Two elements of the ZCode reference are deliberately ABSENT, with the
 * facts that keep them absent:
 *  - «Войти в сессию»: no session-switch API exists in ChatScreen/
 *    ChatViewModel, and spawn-path worker sessions are ephemeral and
 *    deleted when the run ends (T-spawn-subagent-ephemeral) — a button
 *    here would be dead code pointing at a deleted row;
 *  - thinking accordion: AssistantBlock carries NO reasoningContent for
 *    spawn blocks — the worker's reasoning streams inside the worker's
 *    own session and never reaches the parent block. Capturing it is a
 *    cross-session data-path feature, not a UI restyle.
 */
@Composable
internal fun SubagentSessionView(block: AssistantBlock) {
    val args = remember(block.toolArgs) {
        runCatching { org.json.JSONObject(block.toolArgs) }.getOrNull()
    }
    val role = block.toolTitle.ifBlank {
        args?.optString("role").orEmpty().ifBlank { "subagent" }
    }
    val task = args?.optString("task").orEmpty()
    val chip = agentChipOf(block.toolStatus)
    // [T-subagent-block-live] The run's task id is injected into the block
    // args the moment the agent starts — the sheet reads the live progress
    // through the same registry the feed's cards use.
    val runs by AgentRunProgress.snapshots.collectAsState()
    val taskId = args?.optString("taskId").orEmpty()
    val snapshot = taskId.takeIf { it.isNotBlank() }?.let { runs[it] }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // ── Header: role badge + status chip + timer ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.AccountTree,
                contentDescription = null,
                tint = Color(0xFFFF2D55),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                role,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            AgentStatusDot(chip)
            Spacer(Modifier.width(4.dp))
            Text(
                agentChipLabel(chip),
                color = agentChipColor(chip),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (block.durationMs > 0 && agentChipSettled(chip)) {
                Spacer(Modifier.width(8.dp))
                Text(
                    run {
                        val seconds = block.durationMs / 1000.0
                        if (seconds < 10) String.format("%.1fs", seconds)
                        else String.format("%.0fs", seconds)
                    },
                    color = Color(0xFF8B95A5),
                    fontSize = 11.sp,
                )
            }
        }

        // ── Task: styled as the user message bubble that commissioned it ──
        if (task.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "ЗАДАЧА",
                color = Color(0xFF8B95A5),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                color = ChatColors.userBubble,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    task,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.5.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        // ── The agent's own tools, LIVE while the run is alive ──
        if (snapshot != null && snapshot.isRunning && snapshot.nodes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "ТУЛЫ АГЕНТА",
                color = Color(0xFF8B95A5),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            snapshot.nodes.forEach { node ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    AgentNodeStateDot(node.state)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        buildString {
                            append(node.label)
                            node.tool?.let { append(" · $it") }
                            if (node.maxAttempts > 1) append(" · attempt ${node.attempt}/${node.maxAttempts}")
                        },
                        color = Color(0xFFD0D4DC),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // ── Result: the assistant report card ──
        if (agentChipSettled(chip)) {
            Spacer(Modifier.height(10.dp))
            Text(
                "ОТЧЁТ",
                color = Color(0xFF8B95A5),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                color = Color(0xFF16191E),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262A33)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    block.content.ifBlank {
                        if (chip == AgentChip.DONE) "Готово (без текстового вывода)" else "Ошибка без деталей"
                    },
                    color = Color(0xFFD0D4DC),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Рабочая сессия субагента эфемерна: её журнал — task_board и board-артефакт батча.",
            color = Color(0xFF8B95A5),
            fontSize = 11.sp,
        )
    }
}

/**
 * [B34-UI] State dot for one node of the agent's own tool list. Maps the
 * registry's NodeState onto the card's chip palette; BLOCKED reads as
 * waiting (cyan, static), SKIPPED as a dim gray.
 */
@Composable
internal fun AgentNodeStateDot(state: com.openminis.app.offload.AgentRunProgress.NodeState) {
    val (color, pulse) = when (state) {
        com.openminis.app.offload.AgentRunProgress.NodeState.RUNNING ->
            Color(0xFF32ADE6) to true
        com.openminis.app.offload.AgentRunProgress.NodeState.COMPLETED ->
            Color(0xFF34C759) to false
        com.openminis.app.offload.AgentRunProgress.NodeState.FAILED ->
            Color(0xFFFF6B6B) to false
        com.openminis.app.offload.AgentRunProgress.NodeState.BLOCKED ->
            Color(0xFF32ADE6) to false
        com.openminis.app.offload.AgentRunProgress.NodeState.SKIPPED ->
            Color(0xFF8B95A5) to false
    }
    val alpha = if (pulse) {
        val transition = rememberInfiniteTransition(label = "agentNodePulse")
        val p by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "agentNodePulseAlpha",
        )
        p
    } else 1f
    Box(
        modifier = Modifier
            .size(6.dp)
            .background(color.copy(alpha = alpha), CircleShape),
    )
}
