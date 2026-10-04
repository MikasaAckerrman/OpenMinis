package com.openminis.app.engine

/**
 * [T-agent-engine] The engine facade: one object graph per app process,
 * holding the pieces that outlive any single session.
 *
 * Construction is explicit (no globals inside the engine): the app wires
 * the ruleset path, the write policy and the allowlist lookup; sessions
 * receive gates via [gateFor]. The agent-loop factory lands with the loop
 * migration (M7) — the pieces below are already the loop's dependencies.
 */
class AgentEngine(
    val config: EngineConfig,
    val registry: ToolRegistry = ToolRegistry(),
    val todo: TodoStore = TodoStore.shared,
    val logger: EngineLogger = EngineLogger.NONE,
) {
    val hooks: HookEngine = HookEngine(
        rulesetPath = config.hooksPath,
        logger = logger,
    )

    /** A permission gate bound to one session's mode. */
    fun gateFor(mode: PermissionMode): DefaultPermissionGate =
        DefaultPermissionGate(
            mode = mode,
            writePolicy = config.writePolicy,
            allowlist = config.allowlist,
        )
}

data class EngineConfig(
    /** User hook ruleset (default: /var/minis/.config/hooks.json). */
    val hooksPath: String,
    val writePolicy: WritePolicy = WritePolicy.CONSERVATIVE,
    /** Learned user approvals: (toolName, argsJson) previously confirmed. */
    val allowlist: (toolName: String, argsJson: String) -> Boolean = { _, _ -> false },
    val limits: LoopLimits = LoopLimits(),
)
