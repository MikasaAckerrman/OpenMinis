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
 * [B34] The per-agent preview inside ToolDetailSheet — the "мини-окно":
 * role + status chip, the task it was given, the LIVE progress card for a
 * running agent (the same [AgentRunProgressCard] the single-run slot uses —
 * current node, current tool, attempt counter) and the result text for a
 * settled one. The ephemeral note is honest by design: spawn-path workers
 * keep no openable chat session (T-spawn-subagent-ephemeral — their
 * narration lives in the task board and the batch result), so the sheet
 * does not pretend there is a chat to enter.
 */
@Composable
internal fun SubagentDetailContent(block: AssistantBlock) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentStatusDot(chip, size = 8)
            Spacer(Modifier.width(8.dp))
            Text(
                "$role · ${agentChipLabel(chip)}",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        if (task.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(task, color = Color(0xFF8B95A5), fontSize = 13.sp)
        }
        if (chip == AgentChip.RUNNING) {
            Spacer(Modifier.height(10.dp))
            if (snapshot != null) {
                AgentRunProgressCard(snapshot = snapshot)
            } else {
                Text("Работает…", color = Color(0xFF32ADE6), fontSize = 13.sp)
            }
        } else if (block.content.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(block.content, color = Color(0xFFD0D4DC), fontSize = 13.sp)
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                if (chip == AgentChip.DONE) "Готово (без текстового вывода)" else "Ошибка без деталей",
                color = Color(0xFF8B95A5),
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Рабочая сессия субагента эфемерна: её журнал — task_board и board-артефакт батча.",
            color = Color(0xFF8B95A5),
            fontSize = 11.sp,
        )
    }
}
