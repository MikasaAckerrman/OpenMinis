package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * [T-plan-mode] plan_submit — how a PLAN-mode turn ends (the ZCode
 * ExitPlanMode). The agent finishes read-only exploration, submits the full
 * plan as markdown, and the user decides on screen:
 *
 *  - Approve        → the session leaves plan mode (write/execute tools are
 *                     available again from the next schema build) and the
 *                     model is told to follow the approved plan;
 *  - Cancel         → stay in plan mode, keep exploring, revise later;
 *  - free text      → treated as edit feedback: stay in plan mode, revise
 *                     the plan per the feedback and submit again.
 *
 * The tool never executes anything — it only ever asks the user — so it is
 * always allowed, including in plan mode itself (it is the mode's exit).
 * The user's verdict reaches the model verbatim as the tool result; the
 * approval flip is handled by the caller's [onApproved].
 */
object PlanSubmitTool {

    const val NAME = "plan_submit"

    /** The two tap options; free text is always allowed alongside them. */
    const val APPROVE = "Approve"
    const val CANCEL = "Cancel"

    private const val MAX_PLAN_CHARS = 16_000

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Submit your plan for the user to approve. Use this in PLAN mode " +
            "(after read-only exploration) instead of starting the work yourself: send the " +
            "complete plan as markdown — goal, approach, exact steps, files/commands involved, " +
            "risks. The user approves, cancels, or replies with edit feedback on screen. " +
            "On approval plan mode turns off and you execute the plan; on feedback revise and " +
            "submit again. Do NOT call it for trivial one-step asks — just do those after " +
            "approval.",
        parameters = mapOf(
            "plan" to AgentToolParam(
                type = "string",
                description = "The full plan in markdown. Concrete enough to execute verbatim: " +
                    "steps in order, files/paths/commands, what will NOT be touched, risks.",
            ),
            "summary" to AgentToolParam(
                type = "string",
                description = "One-line summary shown in the approval dialog title (the full " +
                    "plan is visible in the tool result block).",
            ),
        ),
        required = listOf("plan"),
    )

    /** The user-verdict seam — AskUserGate in production, fakes in tests. */
    fun interface Confirm {
        suspend fun ask(question: String, options: List<String>): String
    }

    suspend fun execute(
        argsJson: String,
        sessionId: String,
        confirm: Confirm,
        onApproved: () -> Unit,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: malformed arguments", false)
        }
        val plan = args.optString("plan", "").trim()
        if (plan.isEmpty()) {
            return ToolExecutionResult("Error: 'plan' is required — send the complete plan markdown", false)
        }
        val boundedPlan = if (plan.length > MAX_PLAN_CHARS) {
            plan.take(MAX_PLAN_CHARS) + "\n…(plan truncated at $MAX_PLAN_CHARS chars)"
        } else plan
        val summary = args.optString("summary", "").trim()
        val question = if (summary.isNotEmpty()) summary else boundedPlan.take(160)

        val answer = confirm.ask(
            question = "Approve this plan?\n$question",
            options = listOf(APPROVE, CANCEL),
        ).trim()

        // [T-plan-mode-gate-prefix] AskUserGate prefixes its verdicts
        // ("USER ANSWER: <label>" for chips, "USER TEXT: <text>" for free
        // text, "SKIPPED/CANCELLED/REFUSED: …" for dismissals). Normalize
        // BEFORE matching so the real dialog and test fakes (raw labels)
        // take the same branch — the raw-prefix form never equals APPROVE,
        // which silently turned every real approval into edit feedback.
        val verdict = when {
            answer.startsWith("USER ANSWER: ") -> answer.removePrefix("USER ANSWER: ").trim()
            answer.startsWith("USER TEXT: ") -> answer.removePrefix("USER TEXT: ").trim()
            answer.startsWith("SKIPPED") || answer.startsWith("CANCELLED") ||
                answer.startsWith("REFUSED") -> ""
            else -> answer
        }

        return when {
            verdict.equals(APPROVE, ignoreCase = true) -> {
                onApproved()
                ToolExecutionResult(
                    "PLAN APPROVED. Plan mode is OFF — write/execute tools are available again " +
                        "from your next request. Follow the approved plan; a material deviation " +
                        "needs a new plan_submit.\n\nApproved plan:\n$boundedPlan",
                    true,
                )
            }
            verdict.equals(CANCEL, ignoreCase = true) ->
                ToolExecutionResult(
                    "PLAN REJECTED — the user cancelled. You are still in plan mode: keep " +
                        "exploring read-only and submit a revised plan with plan_submit, or ask " +
                        "the user what direction they want instead.",
                    true,
                )
            verdict.isBlank() ->
                ToolExecutionResult(
                    "PLAN NOT APPROVED — no answer arrived. Stay in plan mode; continue " +
                        "read-only exploration or re-submit later.",
                    true,
                )
            else ->
                ToolExecutionResult(
                    "PLAN NOT APPROVED — the user replied with edit feedback:\n$verdict\n\n" +
                        "Stay in plan mode. Revise the plan per the feedback and submit it " +
                        "again with plan_submit.",
                    true,
                )
        }
    }
}
