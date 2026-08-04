package com.openminis.app.offload

import com.openminis.app.data.model.AgentGraph
import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.Handoff
import com.openminis.app.data.model.HandoffStatus

/**
 * [T-agent-graph-memory] Builds the context a node receives, under a budget.
 *
 * The naive approach — paste every upstream handoff verbatim — makes cost grow
 * quadratically with pipeline length: the gatekeeper would receive all twelve
 * previous handoffs in full. On a 13-node graph with two implementer replicas
 * that is most of the token spend, for text the node mostly does not need.
 *
 * The rule here: a node gets its DIRECT predecessors in full (that is its
 * actual input) and everything older as a one-line digest (that is context).
 * A digest keeps what a later agent actually refers back to — who produced
 * what, and whether it was blocked — and drops the prose.
 */
internal object ContextBudget {

    /**
     * Rough token estimate. Deliberately crude: 1 token ≈ 4 chars for English
     * prose and code. Precision would need the provider's tokeniser, which we
     * do not have here — and the budget only needs to be right to ±20% to stop
     * a runaway context.
     */
    fun estimateTokens(text: String): Int = (text.length + 3) / 4

    /**
     * One line per upstream node: enough to know it happened and whether to
     * trust it, nothing more.
     */
    fun digestOf(nodeId: String, handoff: Handoff): String {
        val artifacts = handoff.deliverables
            .take(3)
            .joinToString("; ") { it.take(80) }
        val more = if (handoff.deliverables.size > 3) " (+${handoff.deliverables.size - 3} more)" else ""
        val risk = handoff.risks.firstOrNull()?.take(60)?.let { " | risk: $it" } ?: ""
        return "$nodeId [${handoff.from.name}] ${handoff.status}: $artifacts$more$risk"
    }

    /**
     * Assemble the upstream section of a node's user message.
     *
     * @param directPredecessorIds runtime ids whose handoffs are this node's
     *        actual input — included verbatim.
     * @param olderHandoffs everything else already produced in this run —
     *        included as digests, newest first, until [budgetTokens] runs out.
     */
    fun buildUpstreamContext(
        directPredecessorIds: List<String>,
        handoffs: Map<String, Handoff>,
        olderHandoffs: List<Pair<String, Handoff>>,
        budgetTokens: Int,
    ): String = buildString {
        var spent = 0

        // 1. Direct input, verbatim. This is never trimmed: a node that cannot
        //    see its own input cannot do its job, and silently truncating it
        //    produces confidently wrong output instead of an honest failure.
        for (pid in directPredecessorIds) {
            val h = handoffs[pid] ?: continue
            val block = buildString {
                appendLine("--- INPUT FROM ${h.from.name} (node $pid) ---")
                appendLine(HandoffValidator.buildHandoff(h))
            }
            append(block)
            spent += estimateTokens(block)
        }

        // 2. Older context, digested, newest first, until the budget is gone.
        val digestible = olderHandoffs.filter { (id, _) -> id !in directPredecessorIds }
        if (digestible.isNotEmpty()) {
            val header = "--- EARLIER IN THIS RUN (digest) ---\n"
            append(header)
            spent += estimateTokens(header)

            var included = 0
            for ((id, h) in digestible.asReversed()) {
                val line = digestOf(id, h) + "\n"
                val cost = estimateTokens(line)
                if (spent + cost > budgetTokens) break
                append(line)
                spent += cost
                included++
            }
            val omitted = digestible.size - included
            if (omitted > 0) {
                val note = "($omitted earlier step(s) omitted to stay within the context budget)\n"
                append(note)
            }
        }
    }

    /**
     * Per-node budget for the digested section. Roles that must reason over the
     * whole run get more; roles with a narrow, local job get less, because
     * extra context for them is pure cost.
     */
    fun budgetFor(role: AgentRole, graphBudget: Int): Int = when (role) {
        // Sees the whole chain by definition — its job is the aggregate verdict.
        AgentRole.FINAL_GATEKEEPER -> graphBudget
        // Routes and re-assigns; needs the shape of the run.
        AgentRole.ORCHESTRATOR -> graphBudget
        // Judges tests against findings from several reviewers.
        AgentRole.TEST_QUALITY_AUDITOR -> (graphBudget * 3) / 4
        // Design decisions depend on requirements + discovery, not on reviews.
        AgentRole.SOLUTION_ARCHITECT -> graphBudget / 2
        // Narrow, local jobs: their direct input is nearly everything they need.
        AgentRole.SENIOR_IMPLEMENTER,
        AgentRole.INDEPENDENT_TEST_DESIGNER,
        AgentRole.CODE_CORRECTNESS_REVIEWER,
        AgentRole.SECURITY_REVIEWER,
        AgentRole.PERFORMANCE_REVIEWER,
        AgentRole.DEPENDENCY_GUARDIAN,
        AgentRole.DOCUMENTATION_AGENT,
        AgentRole.REQUIREMENTS_ANALYST,
        AgentRole.CODEBASE_DISCOVERY -> graphBudget / 4
    }
}
