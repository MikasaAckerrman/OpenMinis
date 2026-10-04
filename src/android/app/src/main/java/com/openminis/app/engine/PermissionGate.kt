package com.openminis.app.engine

import org.json.JSONObject

/**
 * [T-permission-gate] Session permission modes — the ZCode plan/edit/yolo
 * contract, engine-side.
 *
 *  - PLAN  — read-only exploration: READ/NETWORK/META tools run freely;
 *    EXECUTE runs only when the specific call is provably read-only (see
 *    [WritePolicy]); WRITE is denied with an instruction to present the
 *    plan. Mutating tools are also stripped from the model-visible schema
 *    by [ToolRegistry.schemaFor] so the model cannot plan around them.
 *  - EDIT  — the default working mode: reads and writes run; executions
 *    that are neither allowlisted nor provably read-only surface a user
 *    confirmation ([GateDecision.Ask] — the dispatcher owns the UI).
 *  - AUTO  — everything runs; the sandbox plus the offload gates remain
 *    the containment. This is the app's historical behavior.
 *
 * The gate is pure policy: it never prompts and never executes. The agent
 * loop asks it before every tool call and renders [GateDecision.Ask]
 * through the session's UI gate.
 */
enum class PermissionMode { PLAN, EDIT, AUTO }

sealed interface GateDecision {
    object Allow : GateDecision

    /** The call needs a user confirmation; the dispatcher owns the prompt. */
    object Ask : GateDecision

    /** Refused. [reason] is addressed to the model, not the user. */
    data class Deny(val reason: String) : GateDecision
}

/**
 * Decides whether a shell-style command mutates state. The app's existing
 * write policy (AgentWritePolicyStore — git-mutation and destructive-command
 * detection) plugs in here; the default is conservative: unknown = mutating.
 */
fun interface WritePolicy {
    fun isMutatingCommand(command: String): Boolean

    companion object {
        val CONSERVATIVE = WritePolicy { true }
    }
}

class DefaultPermissionGate(
    private val mode: PermissionMode,
    private val writePolicy: WritePolicy = WritePolicy.CONSERVATIVE,
    /** Learned user approvals: (toolName, argsJson) previously confirmed. */
    private val allowlist: (toolName: String, argsJson: String) -> Boolean = { _, _ -> false },
) {

    fun check(toolName: String, mutation: MutationKind, argsJson: String): GateDecision =
        when (mode) {
            PermissionMode.AUTO -> GateDecision.Allow
            PermissionMode.EDIT -> when (mutation) {
                MutationKind.READ, MutationKind.NETWORK,
                MutationKind.WRITE, MutationKind.META -> GateDecision.Allow
                MutationKind.EXECUTE -> checkExecute(toolName, argsJson, onMutating = GateDecision.Ask)
            }
            PermissionMode.PLAN -> when (mutation) {
                MutationKind.READ, MutationKind.NETWORK, MutationKind.META -> GateDecision.Allow
                MutationKind.EXECUTE ->
                    checkExecute(toolName, argsJson, onMutating = planDeny(toolName))
                MutationKind.WRITE -> planDeny(toolName)
            }
        }

    /**
     * Whether the tool may appear in the model-visible schema in this mode.
     * EXECUTE stays visible in PLAN (read-only shell calls are legitimate
     * exploration — they are gated per-call); only WRITE is stripped, so
     * the model cannot plan around a tool it will never get.
     */
    fun visibleInSchema(mutation: MutationKind): Boolean =
        !(mode == PermissionMode.PLAN && mutation == MutationKind.WRITE)

    private fun checkExecute(
        toolName: String,
        argsJson: String,
        onMutating: GateDecision,
    ): GateDecision {
        if (allowlist(toolName, argsJson)) return GateDecision.Allow
        val command = commandOf(argsJson) ?: return onMutating
        return if (writePolicy.isMutatingCommand(command)) onMutating else GateDecision.Allow
    }

    /** The shell-style "command" field, when the tool carries one. */
    private fun commandOf(argsJson: String): String? = runCatching {
        JSONObject(argsJson).optString("command", "").trim().ifEmpty { null }
    }.getOrNull()

    private fun planDeny(toolName: String) = GateDecision.Deny(
        "[PLAN MODE] '$toolName' is disabled until the plan is approved. " +
            "Finish your read-only exploration and present the full plan to the " +
            "user; execution resumes after approval.",
    )
}
