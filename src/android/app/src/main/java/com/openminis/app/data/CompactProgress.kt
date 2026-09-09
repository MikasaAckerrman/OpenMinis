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
    MERGING,
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
 * [T-compact-proactive-split] Decide BEFORE the first call whether the
 * transcript obviously cannot fit the compaction model's window.
 *
 * The old flow discovered oversize the slow way: fire a doomed full-size
 * request, wait for the transport to fail (often a 30-60s TTFB timeout on
 * relays that buffer oversized bodies), THEN split and retry — the single
 * biggest contributor to "сжатие очень долгое". Splitting proactively
 * skips the doomed call entirely: each half fits, and the halves run in
 * parallel, so a big session compacts in roughly the time of one half.
 */
object CompactChunking {

    /**
     * True when the estimated transcript tokens would leave less than 40%
     * of the window for the output budget (window/8) + prompt overhead.
     * `chars/4` is the same crude token estimate the rest of the compaction
     * pipeline uses — consistent beats precise here.
     */
    fun shouldSplitProactively(transcriptChars: Int, contextWindowTokens: Int): Boolean {
        val window = contextWindowTokens.coerceAtLeast(1)
        val estTokens = transcriptChars / 4
        return estTokens > window * 6 / 10
    }
}
