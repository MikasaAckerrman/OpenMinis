package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.AskUserGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-ask-user] ask_user — mid-task structured questions (the ZCode
 * "options question"). The tool call SUSPENDS until the user answers on
 * screen: one tap on an option chip, or free text. The choice returns to
 * the model verbatim as the tool result.
 *
 * Use it INSTEAD of a plain-text question in your reply when the answer
 * gates real work: the user answers with one tap while walking, and the
 * turn continues automatically — no "please reply in chat and I'll
 * continue" round-trip (which for the model means the turn ends).
 *
 * Option parsing is lenient on purpose: JSON array of strings, JSON array
 * of {label, description} objects, or a plain "A;B;C" string all work —
 * providers emit tool args with different fidelity.
 */
object AskUserTool {

    const val NAME = "ask_user"
    private const val MAX_OPTIONS = 6

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Ask the user a question with tappable options and WAIT for the " +
            "answer (this tool call blocks until the user responds; the answer becomes " +
            "the tool result). Use when a real decision gates your work — e.g. " +
            "'deploy now or after tests?', 'which of these two designs?'. The user " +
            "answers with one tap or free text. Do NOT use it for open-ended " +
            "conversational questions — just write those in your reply. Max 6 options.",
        parameters = mapOf(
            "question" to AgentToolParam(
                type = "string",
                description = "The question, short and specific — it renders as a dialog title.",
            ),
            "options" to AgentToolParam(
                type = "string",
                description = "Choices. JSON array of strings ['A','B'], array of objects " +
                    "[{\"label\":\"A\",\"description\":\"why\"}], or plain 'A;B;C'. Keep labels short.",
            ),
            "allow_free_text" to AgentToolParam(
                type = "string",
                description = "'true' (default) to also let the user type a custom answer.",
                enumValues = listOf("true", "false"),
            ),
            "timeout" to AgentToolParam(
                type = "integer",
                description = "Seconds to wait for the user (default 0 = forever). Set " +
                    "120-300 in AUTO/BACKGROUND modes with no user present — the tool " +
                    "then returns 'TIMEOUT' and you continue with your best judgment.",
            ),
        ),
        required = listOf("question", "options"),
    )

    suspend fun execute(argsJson: String, sessionId: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: malformed arguments", false)
        }
        val question = args.optString("question", "").trim()
        if (question.isEmpty()) {
            return ToolExecutionResult("Error: 'question' is required", false)
        }
        val options = parseOptions(args.opt("options"))
            ?: return ToolExecutionResult(
                "Error: 'options' is required — JSON array or 'A;B;C'",
                false)
        val allowFreeText = args.optString("allow_free_text", "true") != "false"
        val timeoutSec = args.optInt("timeout", 0).coerceIn(0, 3600)
        // The gate suspends on the MAIN context by design — the dialog
        // renders from Compose; withContext keeps the dispatcher hop honest
        // regardless of the caller's dispatcher.
        val answer = withContext(Dispatchers.Main) {
            if (timeoutSec > 0) {
                kotlinx.coroutines.withTimeoutOrNull(timeoutSec * 1000L) {
                    com.openminis.app.sandbox.AskUserGate.ask(
                        sessionId = sessionId,
                        question = question,
                        options = options,
                        allowFreeText = allowFreeText,
                    )
                } ?: "TIMEOUT: the user did not answer within ${timeoutSec}s — continue with your best judgment."
            } else {
                com.openminis.app.sandbox.AskUserGate.ask(
                    sessionId = sessionId,
                    question = question,
                    options = options,
                    allowFreeText = allowFreeText,
                )
            }
        }
        return ToolExecutionResult(answer, true)
    }

    /** Lenient option parsing — see class doc. Null only when nothing usable arrived. */
    // [T-m12-testable] internal: pure option-format parsing, oracle-tested
    // (JSON arrays, objects, embedded JSON, separator lists, caps).
    internal fun parseOptions(raw: Any?): List<AskUserGate.Option>? {
        when (raw) {
            is JSONArray -> {
                val out = ArrayList<AskUserGate.Option>()
                for (i in 0 until raw.length()) {
                    when (val item = raw.opt(i)) {
                        is String -> if (item.isNotBlank()) out.add(AskUserGate.Option(item.trim()))
                        is JSONObject -> {
                            val label = item.optString("label", "").trim()
                            if (label.isNotEmpty()) {
                                out.add(AskUserGate.Option(label, item.optString("description", "").trim()))
                            }
                        }
                    }
                    if (out.size >= MAX_OPTIONS) break
                }
                return out.ifEmpty { null }
            }
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return null
                // JSON array embedded in a string?
                if (trimmed.startsWith("[")) {
                    runCatching { parseOptions(JSONArray(trimmed)) }.getOrNull()?.let { return it }
                }
                val parts = trimmed.split(';', '|', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(MAX_OPTIONS)
                return parts.ifEmpty { null }?.map { AskUserGate.Option(it) }
            }
        }
        return null
    }
}
