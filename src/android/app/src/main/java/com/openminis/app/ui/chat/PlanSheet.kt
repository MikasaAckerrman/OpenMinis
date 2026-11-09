package com.openminis.app.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.engine.TodoStatus
import com.openminis.app.engine.TodoStore

/**
 * [B46-plan-sheet] The session's working checklist as a MODAL SHEET, not a
 * chat bubble that scrolls away. The agent's todo_write writes
 * [TodoStore]; this sheet collects [TodoStore.observe] — the list updates
 * live, mid-dialog, with zero scroll-hunting and zero model re-reads.
 *
 * States match the tool's own render vocabulary: ✓ done (green,
 * strikethrough), ▸ in-progress (blue, pulsing badge), ○ pending (grey).
 * An empty checklist renders a quiet hint — the sheet only OPENS from the
 * top-bar chip, which itself appears when a plan exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanSheet(
    sessionId: String,
    onDismiss: () -> Unit,
) {
    val items by remember(sessionId) { TodoStore.shared.observe(sessionId) }.collectAsState()
    val done = items.count { it.status == TodoStatus.DONE }
    val total = items.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF16191E),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Текущий план выполнения",
                color = Color(0xFFFFFFFF),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            if (items.isEmpty()) {
                Text(
                    text = "Плана пока нет — агент создаст его через todo_write, как только начнёт многошаговую работу.",
                    color = Color(0xFF8B95A5),
                    fontSize = 13.sp,
                )
                return@Column
            }
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color(0xFF262A33), RoundedCornerShape(3.dp)),
                color = Color(0xFF2AABEE),
                trackColor = Color(0xFF262A33),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Прогресс: $done из $total завершено",
                color = Color(0xFF8B95A5),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.forEach { item ->
                    PlanRow(item.content, item.status)
                }
            }
        }
    }
}

@Composable
private fun PlanRow(content: String, status: TodoStatus) {
    // The in-progress badge pulses only while this row is the active one.
    val pulse = rememberInfiniteTransition(label = "planPulse")
    val pulseAlpha by pulse.animateFloat(
        label = "planPulseAlpha",
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            TodoStatus.DONE -> {
                Text("✓", color = Color(0xFF34C759), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = content,
                    color = Color(0xFF8B95A5),
                    fontSize = 14.sp,
                    textDecoration = TextDecoration.LineThrough,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TodoStatus.IN_PROGRESS -> {
                Text("▸", color = Color(0xFF2AABEE).copy(alpha = pulseAlpha), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = content,
                    color = Color(0xFFFFFFFF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(Color(0xFF2AABEE).copy(alpha = pulseAlpha), CircleShape),
                )
            }
            TodoStatus.PENDING -> {
                Text("○", color = Color(0xFF5B6472), fontSize = 15.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = content,
                    color = Color(0xFF8B95A5),
                    fontSize = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
