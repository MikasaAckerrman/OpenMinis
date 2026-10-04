package com.openminis.app.tools

import org.json.JSONObject

/**
 * [T-m7-path-extractor] The platform's real path extractor for the
 * engine's ToolBatchPlanner: the model's args object carries the target
 * under "path". Lives on the platform side (org.json is an Android SDK
 * dependency — the engine package stays pure Kotlin, the planner takes
 * the extractor as an injected lambda).
 */
internal fun jsonObjectPathExtractor(toolName: String, argsJson: String): String =
    runCatching {
        JSONObject(argsJson).optString("path", "").trim()
    }.getOrNull() ?: ""
