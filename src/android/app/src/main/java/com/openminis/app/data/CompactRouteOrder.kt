package com.openminis.app.data

import com.openminis.app.provider.LLMProvider

/**
 * [T-compact-fast-routes] Speed-tier ranking for the compaction fan-out.
 *
 * User ask (13.10): "нужно сжатие до 1-3 минут максимум, быстро, на
 * серверах". The group's usable peers already contain fast tiers
 * (deepseek-v4-flash:free, glm-5.3-flash, MiniMax-*-highspeed, ministral,
 * mimo-*-flash...) next to heavyweight reasoning models (thinking/pro/opus).
 * Sending the map-phase windows through the BIG models is the slow path:
 * same digest quality (the merge — run by the session-class model — owns
 * fidelity per [T-compact-rolling-append]), 3-5× more wall time per window.
 *
 * This small pure heuristic ranks routes so the map phase starts on fast
 * tiers. It changes ONLY the ORDER of the fan-out start points — every
 * route still appears in the pool, every in-call failure ladder is intact
 * (a refused fast route shakes hands with the next peer per CompactRoute).
 *
 * Context safety: a fast route whose context window cannot hold the
 * largest window (unknown = keep) is demoted to the END of the queue, not
 * dropped — spiel-autonomy preserved, wasted "input too large" handshakes
 * avoided.
 */
object CompactRouteOrder {

    /** Fast tokens that identify high-throughput / low-latency tiers. */
    private val FAST_MARKERS = listOf(
        "flash", "turbo", "highspeed", "high-speed", "lite",
        "ministral", "haiku", "nano", "small",
        // vendor fast-family names known from the live model catalogue
        "mimo", "minimax-m2-highspeed", "gemma-4", "qwen3.8-27b",
    )

    /** Heavy markers — thinking/reasoning tier, slow streams. */
    private val HEAVY_MARKERS = listOf(
        "thinking", "reasoning", "-pro", "opus", "ultra",
        "-max", "preview", "gpt-o", "327b",
    )

    /**
     * Speed rank for a model id (lower = faster). A HEAVY marker wins over
     * a FAST one on collided ids — "mimo-v2.6-pro" is the heavyweight sibling
     * of "mimo-v2.6-flash", and treating it as fast would break the whole
     * point of the tier.
     */
    fun speedRank(modelId: String): Int {
        val id = modelId.lowercase()
        for (m in HEAVY_MARKERS) if (id.contains(m)) return 8
        for (m in FAST_MARKERS) if (id.contains(m)) return 0
        return 5
    }

    /** Tokens an OpenAI-context-window must exceed to hold [needChars] + output. */
    fun safeContext(needChars: Int): Int = needChars / 4 + 2_048

    /**
     * Order the fan-out route pool by speed, respecting context: routes too
     * small for the largest window sink to the end (still usable after the
     * ladder walks past them — bisection halves shrink any window enough).
     * `maxWindowChars` = the largest window the run will send.
     * Order: fast-fit → neutral-fit → slow-fit → fast-too-small → ... ,
     * stable inside ties (group order preserved).
     */
    fun orderRoutes(routes: List<LLMProvider>, maxWindowChars: Int): List<LLMProvider> {
        val needTokens = safeContext(maxWindowChars)
        return routes
            .mapIndexed { idx, p ->
                val fits = (p.model.contextWindow ?: Int.MAX_VALUE) >= needTokens
                val key = speedRank(p.model.id) + if (fits) 0 else 10
                Triple(key, idx, p)
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
    }
}
