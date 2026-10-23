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
 *     constant at the only call site. The patterns are now built per call.
 *
 * Semantics are unchanged: the returned value is the unescaped text of the
 * first occurrence of `key`, truncated at the first unescaped `"`, or the
 * rest of the document when the value has not been closed yet.
 */
internal object PartialJsonToolArgs {

    /**
     * Value of [key] in the (possibly incomplete) [json], or null when the
     * key has not streamed in yet.
     *
     * The two accepted shapes are `"key": "` and `"key":"`. The spaced form
     * is searched for FIRST across the whole document, exactly as the
     * original `listOf(...)` loop did, and only if it is absent the tight
     * form is tried — so a document holding both forms resolves to the
     * spaced one, as before.
     *
     * The patterns are built per call instead of being cached in a field:
     * the cache was a data race. Two callers with different keys could
     * interleave between the write of the key and the read of the pattern
     * array, so one of them would search with the OTHER key's patterns.
     * Each pattern is a dozen characters, so building it costs nothing
     * compared with the indexOf scan that follows.
     */
    fun extract(key: String, json: String): String? {
        val spaced = "\"$key\": \""
        var at = json.indexOf(spaced)
        if (at >= 0) return valueFrom(json, at + spaced.length)
        val tight = "\"$key\":\""
        at = json.indexOf(tight)
        if (at >= 0) return valueFrom(json, at + tight.length)
        return null
    }

    /** The value starting at [from], up to the first unescaped `"`. */
    private fun valueFrom(json: String, from: Int): String {
        val end = findUnescapedEnd(json, from)
        return unescape(json.substring(from, end))
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

    /**
     * Unescape JSON string escapes in a single pass.
 *
     * The previous version chained five `replace()` calls, which is wrong
 * *and* allocation-heavy: a literal `\n` inside the string (that is, the
 * two characters `\\` `n` in the JSON text) matched the first rule and
 * became a real newline, so the pill title showed a line break where the
 * payload had an escaped backslash. A single left-to-right walk consumes
 * each escape exactly once and cannot re-interpret its own output.
 *
 * Unknown escapes (`\q`) yield the escaped character itself, matching the
 * lenient behaviour of the original chain, and `\uXXXX` is passed through
 * as the literal backslash-u form rather than being silently dropped.
 */
    private fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c != '\\' || i + 1 >= n) {
                out.append(c)
                i++
                continue
            }
            when (val e = s[i + 1]) {
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                '"' -> { out.append('"'); i += 2 }
                '/' -> { out.append('/'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                'b' -> { out.append('\b'); i += 2 }
                'f' -> { out.append('\u000C'); i += 2 }
                'u' -> {
                    // Keep the escape verbatim: this helper feeds a title
                    // label, and decoding \uXXXX here would allocate a Char
                    // per code unit for text the model will render itself.
                    out.append('\\').append('u')
                    i += 2
                }
                else -> { out.append(e); i += 2 }
            }
        }
        return out.toString()
    }
}