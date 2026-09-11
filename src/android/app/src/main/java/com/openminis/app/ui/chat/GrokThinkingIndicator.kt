package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay

/**
 * GROK thinking indicator — the verbatim Grok UI:
 *   [dot-matrix Lottie 24×24]  Думаю для 4s
 *
 * - The spinner is Grok's OWN asset (assets/lottie/grok_dot_matrix.json,
 *   extracted verbatim from the Grok APK raw resources — 24×24, 30fps,
 *   40 frames). Played with lottie-compose, no approximation.
 * - The label "Думаю для Ns" ticks per second from the turn start; text
 *   #9e9e9e (measured from the real streaming state in
 *   grok_screenshot_chat), 15sp, NO pill background (verified: the thinking
 *   row paints nothing but text+icon on the background color).
 * - startMs = createdAtMs of the streaming assistant turn (0 → just spin).
 */
@Composable
internal fun GrokThinkingIndicator(
    startMs: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var nowMs by remember(startMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startMs) {
        if (startMs > 0) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    val elapsedSec = if (startMs > 0) ((nowMs - startMs) / 1000L).coerceAtLeast(0L) else 0L

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        val composition by rememberLottieComposition(
            LottieCompositionSpec.Asset("lottie/grok_dot_matrix.json"),
        )
        LottieAnimation(
            composition = composition,
            iterations = Int.MAX_VALUE,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        if (startMs > 0) {
            Text(
                text = context.getString(R.string.chat_thinking_elapsed, elapsedSec),
                fontSize = 15.sp,
                color = ChatColors.streamingText,
            )
        }
    }
}

