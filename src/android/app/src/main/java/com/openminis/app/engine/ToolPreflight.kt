package com.openminis.app.engine

import com.openminis.app.data.model.AgentToolDefinition

/**
 * [T-m7-preflight] The tool-call preflight validator, migrated from
 * ChatViewModel's companion (M7 slice 3).
 *
 * Rejects malformed tool calls BEFORE execution: empty args on a tool
 * that requires anything, missing required fields (absent, explicit
 * null, or empty string outside the whitelist), declared-type
 * violations, and enum mismatches — each as an actionable message the
 * model can self-correct in one round.
 *
 * Pure Kotlin: the caller normalizes the provider's JSON args into
 * [PreflightValue]s (org.json lives on the platform side — same pattern
 * as ToolBatchPlanner's injected path extractor). The 28-case
 * ToolPreflightTest suite runs against the adapter and transitively
 * verifies this engine path with the original semantics.
 */
sealed interface PreflightValue {
    object Null : PreflightValue
    data class Text(val value: String) : PreflightValue
    data class IntNum(val value: Long) : PreflightValue
    data class RealNum(val value: Double) : PreflightValue
    data class Bool(val value: Boolean) : PreflightValue
    /** Structural kinds — content is validated by the tools themselves. */
    object Array : PreflightValue
    object Object : PreflightValue

    /** The model-facing JSON type name ("a string", "an integer"). */
    val jsonTypeName: String
        get() = when (this) {
            is Text -> "a string"
            is Bool -> "a boolean"
            is IntNum -> "an integer"
            is RealNum -> "a number"
            Array -> "an array"
            Object -> "an object"
            Null -> "null"
        }
}

object ToolPreflight {

    /** Required fields that do not gate execution (display-only knobs). */
    private val NON_BLOCKING_FIELDS = setOf("tool_title")

    /** (tool → fields) where "" is a legal VALUE (still must be present). */
    private val EMPTY_STRING_ALLOWED_FIELDS: Map<String, Set<String>> = mapOf(
        "file_edit" to setOf("new_string"),
    )

    /**
     * Validate one call. [toolDef] must be the definition the caller
     * resolved for [name] (null-callers: unknown tools are not this
     * validator's business — the executor reports them). Returns null
     * when the call is well-formed, or the blocking reason.
     */
    fun validate(
        name: String,
        args: Map<String, PreflightValue>,
        toolDef: AgentToolDefinition,
    ): String? {
        val enforced = toolDef.required.filter { it !in NON_BLOCKING_FIELDS }
        if (args.isEmpty() && enforced.isNotEmpty()) {
            return "Tool '$name' was called with empty arguments {} but requires: " +
                "${enforced.joinToString(", ")}."
        }
        val missing = mutableListOf<String>()
        for (field in enforced) {
            val raw = args[field]
            if (raw == null || raw is PreflightValue.Null) {
                missing.add(field)
                continue
            }
            // Only the truly-empty literal "" is rejected — NOT whitespace:
            // file_edit's new_string:"\n" / old_string:"  " are real edits.
            // And "" is legal for whitelisted (tool, field) pairs.
            if (raw is PreflightValue.Text && raw.value.isEmpty() &&
                !emptyStringAllowed(name, field)
            ) {
                missing.add(field)
            }
        }
        if (missing.isNotEmpty()) {
            return "Tool '$name' is missing required parameter(s): ${missing.joinToString(", ")}."
        }
        // Declared-type enforcement: strict rejection with an actionable
        // message converts G13-class silent coercion into a one-round
        // self-correction. Optional params are checked only when PRESENT.
        // Unknown extra fields stay non-errors on purpose.
        for ((field, param) in toolDef.parameters) {
            val raw = args[field] ?: continue
            if (raw is PreflightValue.Null) continue
            val expected = param.type.lowercase()
            val typeOk = when (expected) {
                "string" -> raw is PreflightValue.Text
                // 900.0 (whole) is accepted as an integer — semantically
                // correct; 12.5 is refused. Normalization already maps
                // whole BigDecimal/Double to their closest shapes, so the
                // whole-value check here covers the rest.
                "integer" -> raw is PreflightValue.IntNum ||
                    (raw is PreflightValue.RealNum &&
                        raw.value.isFinite() && raw.value == Math.floor(raw.value))
                "number" -> raw is PreflightValue.IntNum || raw is PreflightValue.RealNum
                "boolean" -> raw is PreflightValue.Bool
                "array" -> raw is PreflightValue.Array
                "object" -> raw is PreflightValue.Object
                else -> true
            }
            if (!typeOk) {
                return "Tool '$name' parameter '$field' must be ${articleFor(expected)} " +
                    "$expected, got ${raw.jsonTypeName}. ${typeFixHint(expected)} " +
                    "Re-issue the call with the corrected JSON type."
            }
            if (param.enumValues != null && raw is PreflightValue.Text &&
                raw.value !in param.enumValues!!
            ) {
                return "Tool '$name' parameter '$field' must be one of " +
                    "[${param.enumValues!!.joinToString(", ")}] (got \"${raw.value}\"). " +
                    "Check the spelling against the list and re-issue the call."
            }
        }
        return null
    }

    /** True when "" is a legal value for this exact (tool, field) pair. */
    fun emptyStringAllowed(tool: String, field: String): Boolean =
        EMPTY_STRING_ALLOWED_FIELDS[tool]?.contains(field) == true

    /** Human article for the preflight message grammar. */
    private fun articleFor(type: String): String =
        if (type.startsWith("i") || type.startsWith("o") || type.startsWith("a")) "an" else "a"

    /** One-line concrete instruction — WHAT to change, not just that it is wrong. */
    private fun typeFixHint(type: String): String = when (type) {
        "string" -> "Send the value as a JSON string (quoted)."
        "integer" -> "Send it as a bare JSON number without quotes (e.g. 900)."
        "number" -> "Send it as a bare JSON number (e.g. 1.5)."
        "boolean" -> "Send true or false unquoted."
        "array" -> "Send a JSON array ([...])."
        "object" -> "Send a JSON object ({...})."
        else -> "Check the declared type."
    }
}
