package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.offload.AgentRunProgress

/**
 * [B6] Status chip of one agent inside the batch card. The block's
 * [ToolBlockStatus] folds into three display buckets: anything still in
 * flight is RUNNING, SUCCESS is DONE, every terminal failure is ERROR.
 */
private enum class BatchChip { RUNNING, DONE, ERROR }

private fun chipOf(status: ToolBlockStatus): BatchChip = when (status) {
    ToolBlockStatus.SUCCESS -> BatchChip.DONE
    ToolBlockStatus.FAILED, ToolBlockStatus.CANCELLED, ToolBlockStatus.TIMEOUT -> BatchChip.ERROR
    else -> BatchChip.RUNNING
}

private fun chipColor(chip: BatchChip): Color = when (chip) {
    BatchChip.RUNNING -> Color(0xFF32ADE6)
    BatchChip.DONE -> Color(0xFF34C759)
    BatchChip.ERROR -> Color(0xFFFF6B6B)
}

private fun chipLabel(chip: BatchChip): String = when (chip) {
    BatchChip.RUNNING -> "RUNNING"
    BatchChip.DONE -> "DONE"
    BatchChip.ERROR -> "ERROR"
}

/** [B6] Header counter: how many agents of the batch have settled. */
internal fun batchProgressLabel(blocks: List<AssistantBlock>): String {
    val done = blocks.count { chipOf(it.toolStatus) != BatchChip.RUNNING }
    return "Субагенты ($done/${blocks.size} завершено)"
}

/**
 * [B6] ONE compact card replacing the N per-agent pills of a `spawn_many`
 * batch: header with the progress counter, a chip per agent (role +
 * status), tap opens the detail sheet. The chat keeps this summary and the
 * orchestrator's own verdict — the per-agent logs moved out of the feed.
 */
@Composable
internal fun SubagentBatchCard(
    item: FlatChatItem.SubagentBatch,
    onOpenDetails: () -> Unit,
) {
    Surface(
        color = Color(0xFF16191E),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262A33)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onOpenDetails() },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AccountTree,
                    contentDescription = null,
                    tint = Color(0xFFFF2D55),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    batchProgressLabel(item.blocks),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "детали",
                    color = Color(0xFF2AABEE),
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.blocks.forEach { block ->
                    val chip = chipOf(block.toolStatus)
                    Row(
                        modifier = Modifier
                            .background(Color(0xFF0D0F12), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(chipColor(chip), CircleShape),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "${block.toolTitle} · ${chipLabel(chip)}",
                            color = Color.White,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * [B6] The detail sheet: one section per agent — role + status chip, the
 * task it was given, the live progress card for running agents (the same
 * [AgentRunProgressCard] the single-run slot uses) and the result text for
 * settled ones. The orchestrator's verdict stays in the chat; this sheet
 * holds the per-agent logs that used to flood it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubagentBatchSheet(
    item: FlatChatItem.SubagentBatch,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF0D0F12),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                batchProgressLabel(item.blocks),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            item.blocks.forEach { block ->
                val chip = chipOf(block.toolStatus)
                val task = runCatching {
                    org.json.JSONObject(block.toolArgs).optString("task", "")
                }.getOrDefault("")
                Surface(
                    color = Color(0xFF16191E),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(chipColor(chip), CircleShape),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${block.toolTitle} · ${chipLabel(chip)}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (task.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                task,
                                color = Color(0xFF8B95A5),
                                fontSize = 12.sp,
                            )
                        }
                        // A running agent with a bound task id shows its live
                        // progress card (the same one the single-run slot uses).
                        if (chip == BatchChip.RUNNING) {
                            val taskId = runCatching {
                                org.json.JSONObject(block.toolArgs).optString("taskId", "")
                            }.getOrDefault("")
                            val runs by AgentRunProgress.snapshots.collectAsState()
                            val snapshot = taskId.takeIf { it.isNotBlank() }?.let { runs[it] }
                            if (snapshot != null) {
                                Spacer(Modifier.height(8.dp))
                                AgentRunProgressCard(snapshot = snapshot)
                            } else {
                                Spacer(Modifier.height(6.dp))
                                Text("Работает…", color = Color(0xFF32ADE6), fontSize = 13.sp)
                            }
                        } else if (block.content.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                block.content,
                                color = Color(0xFFD0D4DC),
                                fontSize = 13.sp,
                            )
                        } else {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (chip == BatchChip.DONE) "Готово (без текстового вывода)" else "Ошибка без деталей",
                                color = Color(0xFF8B95A5),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
