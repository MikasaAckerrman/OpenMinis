package com.openminis.app.provider

/**
 * Normalizes provider-specific output-limit finish reasons and drives the
 * AUTO-EXTENSION contract (user decision 23.09.2026): hitting the output
 * limit mid-answer must CONTINUE seamlessly — the partial answer stays,
 * the model resumes exactly at the cut — instead of dying behind an error
 * banner that the user has to resume by hand.
 */
object OutputLimitPolicy {
    private val limitReasons = setOf(
        "length",
        "max_tokens",
        "max_output_tokens",
    )

    /**
     * Auto-extensions per user message before we fall back to the manual
     * resume banner. Bounded so a model stuck in a reasoning loop can not
     * burn tokens forever: 3 gives long reasoning chains room to land.
     */
    const val MAX_AUTO_EXTENSIONS = 3

    fun reachedLimit(finishReason: String?): Boolean =
        finishReason
            ?.trim()
            ?.lowercase()
            ?.let(limitReasons::contains) == true

    /**
     * The continuation that is parked into the prompt queue when a turn
     * ends on the output limit. The model sees its own truncated reply in
     * context — the ONLY correct behaviour is to resume at the cut, never
     * to re-emit or apologize.
     */
    fun continuationPrompt(extension: Int): String =
        "⟳ Продолжение ответа ($extension/$MAX_AUTO_EXTENSIONS): предыдущий ответ обрезался лимитом выходных токенов. " +
            "Продолжай РОВНО с места обрыва — не повторяй уже написанное, не начинай заново, не извиняйся."
}
