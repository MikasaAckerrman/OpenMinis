package com.openminis.app.data

/**
 * [T-compact-progress] Live state for a running /compact, rendered as a
 * minimal progress card above the composer: phase label, percent, elapsed
 * timer, current model, transient route notes — and, on failure, the
 * SPECIFIC error (which model answered what) instead of a generic
 * "compaction failed".
 *
 * Pure data + pure math, no Android imports: every function here is
 * unit-testable and mirrored in the JVM test suite.
 */

/** Coarse phase of a compact run. */
enum class CompactPhase {
    PREPARING,
    PINNING,
    SUMMARIZING,
    POLISHING,
    WRITING,
    DONE,
}

/** One failed route attempt: model + what it answered. */
data class CompactRouteAttempt(
    val modelId: String,
    val message: String,
)

/** Terminal failure detail shown in the progress card. */
data class CompactFailure(
    val attempts: List<CompactRouteAttempt>,
    val terminal: String,
) {
    /**
     * One compact line naming the actual culprit, e.g.
     * "gpt-5-mini: 401 Invalid API key · gemini-2.5-flash: 429 rate limit".
     * Never the generic "не удалось сжать" the user complained about.
     */
    val summary: String
        get() {
            val route = attempts.joinToString(" · ") { a ->
                "${a.modelId}: ${a.message.take(80)}"
            }
            return if (route.isBlank()) terminal.take(160) else route
        }
}

/** Immutable snapshot of the compact run. */
data class CompactProgress(
    val startMs: Long,
    val phase: CompactPhase = CompactPhase.PREPARING,
    /** 0..99 while running, 100 only on DONE. */
    val percent: Int = 0,
    val chunkIndex: Int = 1,
    val chunkCount: Int = 1,
    val modelLabel: String? = null,
    /** Transient subtitle: "переключаюсь на …", "уменьшаю бюджет …". */
    val routeNote: String? = null,
    /** Set only when the run ended without compacting anything. */
    val failure: CompactFailure? = null,
)

/**
 * Thread-safe aggregator the compaction coroutine reports into; parallel
 * split halves update it concurrently. Emits immutable snapshots through
 * [onUpdate] (the ViewModel funnels them into a StateFlow).
 */
class CompactRunReporter(
    startMs: Long,
    val onUpdate: ((CompactProgress) -> Unit)? = null,
) {
    private var state = CompactProgress(startMs = startMs)
    private var fractions: Map<Int, Float> = mapOf(1 to 0f)

    @Synchronized
    private fun publish(transform: (CompactProgress) -> CompactProgress) {
        state = transform(state)
        onUpdate?.invoke(state)
    }

    @Synchronized
    fun snapshot(): CompactProgress = state

    fun phase(p: CompactPhase) = publish { it.copy(phase = p) }

    fun note(text: String?) = publish { it.copy(routeNote = text) }

    fun model(label: String?) = publish { it.copy(modelLabel = label) }

    /**
     * Announce the start of an LLM call: [chunkIndex] of [chunkCount] on
     * [model]. Resets accumulated fractions when the topology changes (e.g.
     * MERGING is a single 1/1 call after two 1/2+2/2 halves).
     *
     * Note on nested splits (depth > 1): grandchildren reuse the same 1..n
     * index space, so the bar tracks the CURRENT level's calls — percent
     * fidelity degrades gracefully there while phase/timer stay exact.
     * Depth > 1 is the rare path; the common cases (single call, one split
     * + merge) are exact.
     */
    fun callStart(model: String?, chunkIndex: Int, chunkCount: Int) {
        val n = chunkCount.coerceAtLeast(1)
        val topologyChanged = n != state.chunkCount
        if (topologyChanged) {
            fractions = buildMap { for (i in 1..n) put(i, 0f) }
        }
        publish {
            it.copy(
                modelLabel = model,
                chunkCount = n,
                chunkIndex = chunkIndex.coerceIn(1, n),
                // A new topology means the bar restarts from zero.
                percent = if (topologyChanged) 0 else it.percent,
            )
        }
    }

    /**
     * Report progress of chunk [index] (1-based): [chars] produced out of an
     * estimated [targetChars]. Fraction is clamped to 0.99 so the bar never
     * reads "full" before the phase actually completes.
     */
    fun callChars(index: Int, chars: Int, targetChars: Int) {
        val n = state.chunkCount.coerceAtLeast(1)
        val idx = index.coerceIn(1, n)
        val target = targetChars.coerceAtLeast(1)
        val f = (chars.toFloat() / target).coerceIn(0f, 0.99f)
        fractions = fractions.toMutableMap().also { it[idx] = f }
        val sum = (1..n).sumOf { (fractions[it] ?: 0f).toDouble() }
        val pct = ((sum / n) * 100.0).toInt().coerceIn(0, 99)
        publish { it.copy(chunkIndex = idx, percent = pct) }
    }

    fun done() = publish { it.copy(phase = CompactPhase.DONE, percent = 100) }
}

/**
 * Pure helpers for the progress card (percent text, elapsed clock, token
 * counts). Kept here so the UI stays dumb and the math is testable.
 */
object CompactMath {

    /** "0:07" / "1:23" / "12:05". */
    fun formatElapsed(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) / 1000).toInt()
        val m = totalSec / 60
        val s = totalSec % 60
        return "$m:" + s.toString().padStart(2, '0')
    }

    /** 12_345 -> "12.3k", 900 -> "900". */
    fun formatTokens(n: Int): String = when {
        n >= 1000 -> {
            val k = n / 1000.0
            if (k >= 10) "${k.toInt()}k" else String.format("%.1fk", k)
        }
        else -> n.toString()
    }
}

/**
 * [T-compact-window-packing] Size-driven chunking for the compaction request.
 *
 * Replaces the old "halve the message list + depth cap 3" strategy, which had
 * two fatal flaws exposed by a live gateway rejection ("запрос отклонен
 * шлюзом", surfaced verbatim in the failure card):
 *
 * 1. It only triggered on RECOGNIZED error text — English/Chinese markers.
 *    A Russian-speaking relay matched nothing, so the very first rejection
 *    surfaced raw, and every "Повторить" resent the identical doomed body.
 * 2. Halving by MESSAGE COUNT cannot reduce a skewed transcript (few huge
 *    messages) and the depth cap stranded oversized chunks.
 *
 * The new contract: control flow is driven by SIZE, never by error wording.
 * The transcript is packed into windows that fit the per-call budget BEFORE
 * the first request; wording only affects display and the bounded halving
 * safety net.
 */
object CompactChunking {

    /**
     * Hard per-call input cap independent of the model window. JSON escaping
     * of code/logs can inflate a body ×4+ (quotes, newlines, unicode); new-api
     * relays fronted by nginx default to a 1MB client_max_body_size. 96k raw
     * chars stay under that even at worst-case escaping.
     */
    const val MAX_RELAY_BODY_CHARS = 96_000

    /** Below this a rejected window is not worth halving — the body is tiny. */
    const val MIN_HALVABLE_CHARS = 4_000

    /**
     * Per-call input budget: 2 chars per window token (chars/4 token estimate
     * → input ≤ 50% of the window, leaving room for the rolling summary, the
     * system prompt and the output budget), clamped by the relay body cap.
     */
    fun perCallInputCapChars(contextWindowTokens: Int): Int =
        minOf(contextWindowTokens.coerceAtLeast(1) * 2, MAX_RELAY_BODY_CHARS)

    /**
     * Pack [text] into windows of at most [capChars], preferring line
     * boundaries. A single line longer than the cap is split by characters —
     * no input, however skewed, can produce an oversized window. Blank
     * windows are dropped. Returns an empty list only for blank input.
     */
    fun packWindows(text: String, capChars: Int): List<String> {
        val cap = capChars.coerceAtLeast(1)
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (line in text.split('\n')) {
            var remaining = line
            while (remaining.length > cap) {
                // Flush the accumulated prefix first so the oversized line
                // starts a fresh window at a clean boundary.
                if (sb.isNotEmpty()) {
                    out += sb.toString()
                    sb.setLength(0)
                }
                out += remaining.substring(0, cap)
                remaining = remaining.substring(cap)
            }
            if (sb.length + remaining.length + 1 > cap && sb.isNotEmpty()) {
                out += sb.toString()
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(remaining)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out.filter { it.isNotBlank() }
    }
}
