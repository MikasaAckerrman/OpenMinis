package com.openminis.app.offload

/**
 * Per-node ceiling on tool calls, so a role cannot spend a run doing another
 * role's job.
 *
 * ## Why
 *
 * `AgentNode.maxTurns` has existed since the graph model was written and was
 * never read by anything — a dead field in every built-in node. Measured
 * consequence, run 9eb70345 on 2026-08-10: the ORCHESTRATOR ("You plan. You do
 * not implement.") spent 3 minutes and 14 model calls walking the codebase, its
 * request body growing 7 KB → 99 KB, and produced no plan before the user gave
 * up. Nothing was broken; nothing stopped it either.
 *
 * A planner does need to look before planning — a plan written blind is a guess
 * with formatting. So this is a budget, not a ban: the first N calls are free,
 * and past N the executor refuses with an instruction to deliver the artifact
 * using what it already read. Refusing rather than killing the node matters —
 * the model gets a chance to finish properly, and a run that produced a plan
 * from five files beats a cancelled run that read fifty.
 *
 * Pure and Android-free so the arithmetic is unit-tested; the process-wide
 * counter lives in [com.openminis.app.tools.AgentToolBudgetStore].
 */
object AgentToolBudget {

    /**
     * Verdict for one attempted tool call.
     *
     * @param allowed whether the call may proceed.
     * @param message refusal text handed to the model, empty when allowed.
     */
    data class Verdict(val allowed: Boolean, val message: String = "")

    /**
     * A node with no configured budget gets this. Deliberately generous: the
     * point is to stop a role wandering for minutes, not to micro-manage a
     * legitimate multi-step edit.
     */
    const val DEFAULT_BUDGET: Int = 12

    /**
     * @param used tool calls already made by this node.
     * @param budget ceiling, from the node's `maxTurns`; <= 0 means unlimited.
     * @param roleLabel human name used in the refusal ("Orchestrator").
     * @param artifact what this node owes, echoed so the refusal is actionable.
     */
    fun check(
        used: Int,
        budget: Int,
        roleLabel: String,
        artifact: String,
    ): Verdict {
        // A non-positive budget means "unbounded" rather than "no tools": a
        // graph author who omitted the field must not silently mute the node,
        // the same reasoning as AgentToolPolicyStore's empty allowlist.
        if (budget <= 0) return Verdict(allowed = true)
        if (used < budget) return Verdict(allowed = true)
        return Verdict(
            allowed = false,
            message = "Tool budget spent: $roleLabel used $used of $budget tool calls for " +
                "this task. Stop gathering and deliver your artifact now — $artifact — " +
                "using what you already read. If a genuine blocker means you cannot, hand " +
                "off with STATUS: BLOCKED and name exactly what is missing. Do not call " +
                "another tool.",
        )
    }

    /**
     * Warn one call before the wall, so the model can wrap up on its own terms
     * instead of being cut off mid-thought.
     *
     * Returns null when no warning is due. Kept separate from [check] because a
     * warning rides along with a SUCCESSFUL call — mixing the two would mean
     * either refusing early or warning too late to be useful.
     */
    fun warningFor(used: Int, budget: Int): String? {
        if (budget <= 0) return null
        val remaining = budget - used
        if (remaining != 1) return null
        return "Budget notice: this was tool call $used of $budget. One call remains — " +
            "spend it only if it is essential, then deliver your artifact."
    }
}
