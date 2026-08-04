package com.openminis.app.offload

import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.Handoff
import com.openminis.app.data.model.HandoffStatus

/**
 * Validates and parses the structured handoff protocol.
 * Protocol format (from v4 spec):
 * ```
 * === HANDOFF START ===
 * FROM: [Exact Agent Role Name]
 * TO: [Exact Target Agent Role Name]
 * TASK_ID: [short unique id]
 * STATUS: COMPLETE | BLOCKED | NEEDS_CLARIFICATION
 * DELIVERABLES:
 * - [list of concrete artifacts produced]
 * SUCCESS_CRITERIA_MET:
 * - [what was verified]
 * REMAINING_RISKS_OR_OPEN_QUESTIONS:
 * - [if any]
 * NEXT_REQUIRED_ACTION:
 * [one clear sentence what the receiving agent must do]
 * === HANDOFF END ===
 * ```
 */
object HandoffValidator {

    private const val START_MARKER = "=== HANDOFF START ==="
    private const val END_MARKER = "=== HANDOFF END ==="

    /** Parse a handoff from agent response text. Returns null if not found or invalid. */
    fun parseHandoff(text: String): Handoff? {
        val startIdx = text.indexOf(START_MARKER)
        if (startIdx == -1) return null
        val endIdx = text.indexOf(END_MARKER, startIdx)
        if (endIdx == -1) return null

        val handoffText = text.substring(startIdx + START_MARKER.length, endIdx).trim()
        return parseHandoffContent(handoffText)
    }

    /** Parse the inner content of a handoff block. */
    private fun parseHandoffContent(content: String): Handoff? {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var from: AgentRole? = null
        var to: AgentRole? = null
        var taskId = ""
        var status: HandoffStatus? = null
        val deliverables = mutableListOf<String>()
        val successCriteria = mutableListOf<String>()
        val risks = mutableListOf<String>()
        var nextAction = ""

        var currentSection = ""
        for (line in lines) {
            if (line.startsWith("FROM:")) {
                from = runCatching { AgentRole.valueOf(line.substring(5).trim()) }.getOrNull()
            } else if (line.startsWith("TO:")) {
                to = runCatching { AgentRole.valueOf(line.substring(3).trim()) }.getOrNull()
            } else if (line.startsWith("TASK_ID:")) {
                taskId = line.substring(8).trim()
            } else if (line.startsWith("STATUS:")) {
                status = runCatching { HandoffStatus.valueOf(line.substring(7).trim()) }.getOrNull()
            } else if (line == "DELIVERABLES:") {
                currentSection = "DELIVERABLES"
            } else if (line == "SUCCESS_CRITERIA_MET:") {
                currentSection = "SUCCESS_CRITERIA_MET"
            } else if (line == "REMAINING_RISKS_OR_OPEN_QUESTIONS:") {
                currentSection = "RISKS"
            } else if (line == "NEXT_REQUIRED_ACTION:") {
                currentSection = "NEXT_ACTION"
            } else if (line.startsWith("- ")) {
                val item = line.substring(2).trim()
                when (currentSection) {
                    "DELIVERABLES" -> deliverables.add(item)
                    "SUCCESS_CRITERIA_MET" -> successCriteria.add(item)
                    "RISKS" -> risks.add(item)
                }
            } else if (currentSection == "NEXT_ACTION") {
                nextAction = line
            }
        }

        if (from == null || to == null || taskId.isEmpty() || status == null) {
            return null
        }

        return Handoff(
            from = from,
            to = to,
            taskId = taskId,
            status = status,
            deliverables = deliverables,
            successCriteria = successCriteria,
            risks = risks,
            nextAction = nextAction,
        )
    }

    /** Build a handoff text block from structured data. */
    fun buildHandoff(handoff: Handoff): String {
        val sb = StringBuilder()
        sb.appendLine(START_MARKER)
        sb.appendLine("FROM: ${handoff.from.name}")
        sb.appendLine("TO: ${handoff.to.name}")
        sb.appendLine("TASK_ID: ${handoff.taskId}")
        sb.appendLine("STATUS: ${handoff.status.name}")
        sb.appendLine("DELIVERABLES:")
        for (d in handoff.deliverables) sb.appendLine("- $d")
        sb.appendLine("SUCCESS_CRITERIA_MET:")
        for (s in handoff.successCriteria) sb.appendLine("- $s")
        sb.appendLine("REMAINING_RISKS_OR_OPEN_QUESTIONS:")
        for (r in handoff.risks) sb.appendLine("- $r")
        sb.appendLine("NEXT_REQUIRED_ACTION:")
        sb.appendLine(handoff.nextAction)
        sb.appendLine(END_MARKER)
        return sb.toString()
    }

    /** Validate that a response contains a properly formatted handoff. */
    fun validateResponse(text: String): ValidationResult {
        val handoff = parseHandoff(text)
        if (handoff == null) {
            return ValidationResult(false, "No valid handoff block found in response")
        }
        // Additional semantic checks
        if (handoff.nextAction.isBlank()) {
            return ValidationResult(false, "NEXT_REQUIRED_ACTION is empty")
        }
        if (handoff.status == HandoffStatus.COMPLETE && handoff.deliverables.isEmpty()) {
            return ValidationResult(false, "COMPLETE handoff must have at least one deliverable")
        }
        return ValidationResult(true, "Valid", handoff)
    }

    data class ValidationResult(
        val isValid: Boolean,
        val message: String,
        val handoff: Handoff? = null,
    )

    /** Extract JSON artifact paths from deliverables (for workspace persistence). */
    fun extractArtifactPaths(deliverables: List<String>): List<String> {
        return deliverables.mapNotNull { d ->
            // Look for paths like /var/minis/workspace/... or minis://workspace/...
            val pathRegex = "(/var/minis/workspace/[^\\s]+|minis://workspace/[^\\s]+)".toRegex()
            pathRegex.find(d)?.value
        }.distinct()
    }
}