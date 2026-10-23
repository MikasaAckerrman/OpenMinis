package com.openminis.app.ui.chat

/**
 * Partial-JSON helpers for the streaming tool-input pill header.
 *
 * Extracted from [ChatViewModel] so the logic is unit-testable on the JVM and
 * so the hot path carries no per-call garbage.
 *
 * [T-gc-churn] Two allocation problems were fixed when this moved out:
 *
 *  1. `findUnescapedEnd` used to receive `json.substring(at + pattern.length)`
 *     — a copy of the WHOLE remainder of the document — purely to scan a
 *     value that is normally a few dozen characters. It runs on every
 *     ToolInputDelta gate opening (once per second while a file_write payload
 *     streams), so a multi-KB tool argument allocated megabytes of garbage
 *     while the pill title never changed. It now scans in place and returns
 *     an index.
 *  2. `extractPartialStringValue` rebuilt `listOf("\"$key\": \"", …)` on every
 *     call, allocating a List plus two interpolated Strings for a key that is
 *     constant at the only call site. The patterns are now built once per key.
 *
 * Semantics are unchanged: the returned value is the unescaped text of the
 * first occurrence of `key`, truncated at the first unescaped `"`, or the
 * rest of the document when the value has not been closed yet.
 */
internal object PartialJsonToolArgs {

    private var lastKey: String? = null
    private var lastPatterns: Array<String> = emptyArray()

    /** Patterns for [key], rebuilt only when the key actually changes. */
    private fun patternsFor(key: String): Array<String> {
        if (key != lastKey) {
            lastKey = key
            lastPatterns = arrayOf("\"$key\": \"", "\"$key\":\"")
        }
        return lastPatterns
    }

    /**
     * Value of [key] in the (possibly incomplete) [json], or null when the
     * key has not streamed in yet.
     */
    fun extract(key: String, json: String): String? {
        for (p in patternsFor(key)) {
            val at = json.indexOf(p)
            if (at < 0) continue
            val from = at + p.length
            val end = findUnescapedEnd(json, from)
            return unescape(json.substring(from, end))
        }
        return null
    }

    /** Index of the first unescaped `"` at or after [from], or [json].length. */
    private fun findUnescapedEnd(json: String, from: Int): Int {
        var i = from
        val n = json.length
        while (i < n) {
            val c = json[i]
            if (c == '\\') {
                // Skip the escaped character (`\"`, `\\`, `\n`, …). A trailing
                // backslash pushes i past the end, which ends the scan exactly
                // as the old copy-and-scan version did.
                i += 2
                continue
            }
            if (c == '"') return i
            i++
        }
        return n
    }

    /** Unescape the common JSON string escapes. */
    private fun unescape(s: String): String {
        // Five chained replace() calls allocate five Strings even when there
        // is nothing to unescape; nothing can be escaped without a backslash.
        if (s.indexOf('\\') < 0) return s
        return s.replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
    }
}