package com.openminis.app.agent

/**
 * [T-auto-mode] Pure policy for the auto-mode engine: the user's contract is
 * "он не остановится, пока всё не сделает" — the agent keeps working through
 * a plan of ANY size, stopping only when the plan is completed in the best
 * achievable way, not when turns run out.
 *
 * The loop is driven by ChatViewModel.maybeAutoContinue(): after every
 * successful turn, if the run is armed and the model has NOT declared
 * completion, a continuation prompt is auto-sent. Completion is an explicit
 * contract: the model's FINAL turn ends with [SENTINEL] alone on its last
 * line — an unambiguous machine-checkable signal that survives markdown.
 *
 * Deliberately pure (no Android, no I/O) so the contract is unit-tested:
 * arming phrases, completion detection, budget caps, prompt text.
 *
 * Token budget: v1 bounds the run by turn count + the context-pressure
 * system (auto-compact folds history at 100% instead of letting the session
 * grow forever). A true token ledger lands together with persisting per-turn
 * usage — MessageEntity.tokenUsage exists but is not written yet (dead
 * column), so an exact sum is impossible today; the caps below are the
 * honest bound we can actually enforce.
 */
object AgentAutoMode {

    /** The completion contract: last line of the final turn, nothing else. */
    const val SENTINEL = "TASK_COMPLETE"

    /**
     * Hard stop: continuations sent in one armed run. 500 × a full agent turn
     * is far beyond any sane plan; this is a runaway-loop breaker, not a
     * realistic workload bound.
     */
    const val MAX_AUTO_TURNS = 500

    /** The prompt asks the agent to checkpoint (memory + git) at this cadence. */
    const val CHECKPOINT_EVERY_TURNS = 15

    /** Arming phrases the user may write naturally (case-insensitive). */
    private val ARM_PHRASES = listOf(
        "авто-режим", "авто режим", "auto mode", "автомод", "/auto",
    )

    /** True when the user's own words ask for an autonomous run. */
    fun wantsAutoMode(userText: String): Boolean {
        val lower = userText.lowercase()
        return ARM_PHRASES.any { lower.contains(it) }
    }

    /**
     * Completion = the sentinel stands alone on a line of the turn's text.
     * Tolerates surrounding whitespace and case; requires its own line so a
     * mere mention ("TASK_COMPLETE ниже") cannot end the run.
     */
    fun isPlanComplete(lastAssistantText: String): Boolean =
        lastAssistantText.lineSequence().any { it.trim().equals(SENTINEL, ignoreCase = true) }

    /**
     * Gate evaluated after each successful turn.
     * [contextFull] pauses the loop while compaction catches up (the send
     * queue already parks messages during a compact — same machinery).
     */
    fun shouldContinue(
        armed: Boolean,
        turnsUsed: Int,
        lastAssistantText: String,
        contextFull: Boolean,
    ): Boolean = armed &&
        turnsUsed < MAX_AUTO_TURNS &&
        !isPlanComplete(lastAssistantText) &&
        !contextFull

    /**
     * The continuation prompt — the whole behavioural contract of the loop,
     * refreshed every turn so long runs never drift from it. The VERIFY
     * block is the machine half of completion: the engine executes the
     * criteria itself (see [AutoModeVerification]) — TASK_COMPLETE counts
     * only when they pass.
     */
    fun continuationPrompt(turn: Int): String = buildString {
        appendLine("⟳ Авто-режим · продолжение $turn из $MAX_AUTO_TURNS")
        appendLine()
        appendLine("Продолжай выполнять план — автономно, до ПОЛНОГО и наилучшего завершения. Правила:")
        appendLine("1. Не спрашивай меня ничего: принимай решения сам, фиксируй их в памяти (memory_write) и продолжай.")
        appendLine("2. Каждые $CHECKPOINT_EVERY_TURNS продолжений — чекпоинт: запиши прогресс в память и сделай git-коммит, затем продолжай.")
        appendLine("3. Не останавливайся на «примерно готово»: доводи каждый пункт до проверенного результата.")
        appendLine("4. Каждое продолжение с реальной работой заканчивай блоком (движок проверит его сам):")
        appendLine("   VERIFY:")
        appendLine("   files: <пути через запятую — обязаны существовать>")
        appendLine("   absent: <пути — обязаны отсутствовать>")
        appendLine("   cmd: <однострочная команда; код выхода 0 = пройдено>")
        appendLine("   Ключи опциональны — но по крайней мере один, для проверяемой части работы.")
        appendLine("5. Когда план выполнен наилучшим образом — напиши финальный итог (что сделано, чем подтверждено, что осталось вне охвата), приведи финальный VERIFY-блок и последней строкой отправь ровно:")
        appendLine(SENTINEL)
        appendLine("   TASK_COMPLETE засчитывается только если VERIFY прошёл: менеджер проверяет, а не верит. Никогда не пиши $SENTINEL раньше реального завершения.")
        appendLine("6. Если VERIFY провалился — исправь причину и добейся прохождения; после ${AutoModeVerification.MAX_FAILS} провалов подряд движок потребует смены стратегии.")
        appendLine("7. Если упёрся в блокер, который сам обойти не можешь — опиши его, зафиксируй в памяти и продолжай доступную часть плана.")
    }

    /**
     * The replan prompt: forced strategy change after [AutoModeVerification.MAX_FAILS]
     * consecutive verification failures. A fourth identical attempt is
     * token burn; the supervisor turn must find the ROOT CAUSE, propose a
     * different approach and continue under it.
     */
    fun replanPrompt(replanIndex: Int, failures: List<String>): String = buildString {
        appendLine("⟳ Авто-режим · REPLAN #$replanIndex из ${AutoModeVerification.MAX_REPLANS} — смена стратегии")
        appendLine()
        appendLine("Текущий подход провалил машинную проверку ${AutoModeVerification.MAX_FAILS} раз подряд:")
        failures.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Четвёртая попытка тем же путём запрещена. Твоя задача сейчас:")
        appendLine("1. Назвать ПЕРВОПРИЧИНУ (не симптом): почему подход не проходит проверку.")
        appendLine("2. Предложить ДРУГУЮ стратегию — не вариацию той же, а принципиально иную (другой путь, другой механизм, другой порядок).")
        appendLine("3. Обновить план в памяти (memory_write) и продолжить работу уже по новой стратегии.")
        appendLine("Правила продолжений действуют как раньше: VERIFY-блок, чекпоинты, TASK_COMPLETE только после прохождения проверки.")
    }
}
