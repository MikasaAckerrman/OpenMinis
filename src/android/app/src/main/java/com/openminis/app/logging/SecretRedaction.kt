package com.openminis.app.logging

/**
 * [B14-secret-redaction] Central secret masking for the AppLogger file
 * sinks. Before this, redaction existed only as point-fixes
 * (OAuthManager.sanitizeBody for token-exchange bodies); any other line —
 * a provider echoing an Authorization header, a shell tool printing a
 * copy-pasted curl command, a stack trace carrying a credential in its
 * message, framework output captured by the logcat tailer — landed
 * verbatim in minis-YYYY-MM-DD.log, which the user can export from
 * LogManagementScreen.
 *
 * Conservative by design: only patterns that are (a) high-confidence
 * credentials and (b) practically immune to false positives on normal
 * diagnostic text. Deliberately NO generic key=value / query-string net —
 * it mangles ids, paths and diagnostic content without catching anything
 * the specific patterns miss (a secret with no recognizable prefix is not
 * detectable in the first place). Widen the pattern list only with a
 * reproducing log line in hand.
 *
 * Pure JVM (no Android imports) — unit-testable in isolation.
 */
object SecretRedaction {

    private const val MASK = "***"

    // Bearer scheme (Authorization headers, copy-pasted curl lines). The
    // payload must be ≥12 chars AND contain at least one digit — real
    // tokens (JWT, hex, base64url of random bytes) satisfy both with
    // overwhelming probability, while English phrases like "bearer token
    // rotation" or "Bearer authentication" survive untouched (letters only,
    // or short).
    private val BEARER = Regex("""(?i)(\bbearer\s+)([A-Za-z0-9._~+/=-]{12,})""")

    // OpenAI-family keys ("sk-...", covers "sk-ant-api03-..." too — the
    // payload charset MUST include '-', or the hyphenated Anthropic
    // spelling breaks the run at "sk-ant" and never matches). The lookbehind
    // keeps words that merely CONTAIN "sk-" ("task-<20 chars>") from
    // matching: an alphanumeric char before "sk-" disqualifies the match.
    private val SK = Regex("""(?<![A-Za-z0-9])sk-[A-Za-z0-9-]{20,}""")

    // GitHub token family: ghp_ (PAT), gho_ (OAuth), ghu_ (user-to-server),
    // ghs_ (server-to-server), ghr_ (refresh). Same lookbehind discipline.
    private val GH = Regex("""(?<![A-Za-z0-9])gh[pousr]_[A-Za-z0-9]{20,}""")

    // Minis' own debug-server token, in header or key=value spellings.
    // The key itself is kept (grep-ability), only the value is masked.
    private val XMINIS = Regex("""(?i)(x-minis-token["']?\s*[:=]\s*)([A-Za-z0-9._-]+)""")

    fun mask(input: String): String {
        if (input.length < 12) return input
        var out = BEARER.replace(input) { m ->
            if (m.groupValues[2].any { it.isDigit() }) m.groupValues[1] + MASK else m.value
        }
        out = SK.replace(out, MASK)
        out = GH.replace(out, MASK)
        out = XMINIS.replace(out) { m -> m.groupValues[1] + MASK }
        return out
    }
}
