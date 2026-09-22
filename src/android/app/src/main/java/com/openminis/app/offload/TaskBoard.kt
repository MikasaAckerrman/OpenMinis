package com.openminis.app.offload

/**
 * [T-task-board] Formatting rules for the spawn_many task board — the cheap
 * coordination layer between parallel workers (deepseek-harness Task Board
 * pattern, adapted: the executor owns the board, workers never write it, so
 * append-only is guaranteed by code, not by prompt discipline).
 *
 * Pure by design: the board shape is a contract between the orchestrator,
 * the workers and the synthesizer, so it is unit-testable off-device.
 */
object TaskBoard {

    /** Board header: the plan of record for the whole batch. */
    fun header(agents: List<String>): String = buildString {
        appendLine("# Task board")
        appendLine()
        agents.forEachIndexed { i, role -> appendLine("- ${i + 1}. $role") }
        appendLine()
    }

    /** One completed agent's entry, appended by the executor. */
    fun entry(order: Int, role: String, result: String): String = buildString {
        appendLine("### $order. $role")
        appendLine(result.trim().take(ENTRY_MAX_CHARS))
        appendLine()
    }

    /**
     * What a worker receives: its own task plus the board as of its batch
     * start. Same-batch workers are conflict-free independents, so they see
     * only PREVIOUS batches — the correct amount of context, not a
     * mid-flight race.
     */
    fun inject(task: String, boardSnapshot: String): String =
        if (boardSnapshot.isBlank()) task
        else task + "\n\n" + CONTEXT_HEADER + "\n" + boardSnapshot.take(SNAPSHOT_MAX_CHARS)

    const val ENTRY_MAX_CHARS = 1200
    const val SNAPSHOT_MAX_CHARS = 3000

    private const val CONTEXT_HEADER =
        "FINDINGS SO FAR from earlier agents in this batch (their results — " +
            "use them as context, do not redo their work; ignore what is " +
            "irrelevant to your task):"
}
