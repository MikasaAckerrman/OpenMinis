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

/**
 * [T-tool-scheduler] The platform's resource-key extractor for the
 * engine's ToolScheduler. Declared resources:
 *  - fs tools -> {"fs:<abs path>"} (path arg; missing/blank path stays
 *    UNDECLARED — the conservative global lock, never an empty key that
 *    would silently ride a parallel wave);
 *  - memory journal writes -> {"memory:journal"} (append-only file = one
 *    writer at a time, model order);
 *  - everything else -> null (GLOBAL lock: serialize alone).
 * Returns a SET because future tools may span multiple resources
 * (db row + http host); the scheduler treats any intersection as a
 * conflict.
 */
internal fun jsonObjectResourceKeys(toolName: String, argsJson: String): Set<String>? = runCatching {
    when (toolName) {
        "file_read", "file_write", "file_edit", "read_image" -> {
            val p = JSONObject(argsJson).optString("path", "").trim()
            if (p.isEmpty()) null else setOf("fs:$p")
        }
        "memory_write", "memory_blocks_edit" -> setOf("memory:journal")
        else -> null
    }
}.getOrNull() ?: null
