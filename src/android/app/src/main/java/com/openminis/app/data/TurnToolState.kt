package com.openminis.app.data

/**
 * What the auto-resume decision needs to know about ONE turn: the
 * assistant message this turn writes to, and how many tool calls the
 * engine has finished in it.
 *
 * The state belongs to a single turn on purpose. A counter in the view
 * model would be shared by every turn that runs at the same time, and a
 * tool call of an old turn would then forbid the replay of a fresh one.
 * A turn that starts resets the counter, so the decision only ever sees
 * the calls of the turn that died.
 */
class TurnToolState {
    /** The assistant message the turn writes to right now. */
    @Volatile
    var assistantId: String? = null
        private set

    /** Tool calls the engine finished in this turn. */
    @Volatile
    var toolCalls: Int = 0
        private set

    /**
     * A new turn started. It writes to [assistantId] and has not called a
     * tool yet.
     */
    fun onTurnStart(assistantId: String) {
        this.assistantId = assistantId
        this.toolCalls = 0
    }

    /**
     * The turn was split and continues in a new message. The calls of the
     * first part still belong to this turn, so the counter is kept.
     */
    fun onAssistantIdChanged(assistantId: String) {
        this.assistantId = assistantId
    }

    /**
     * The engine finished a tool call. The tool result may not be in the
     * message list yet - this is the only place that knows for sure that a
     * tool ran.
     */
    fun onToolCallFinished() {
        toolCalls++
    }
}
