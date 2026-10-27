package com.openminis.app.ui.browser

/**
 * [T-telegram-in-app] Telegram links must stay inside Minis.
 *
 * A `tg://` deep link used to be dispatched to the system handler, which
 * sent the user out of the app into their Telegram client; a `t.me` web
 * link was opened by the system browser. Both have a public web form, and
 * the in-app browser renders it, so the link is rewritten to that form and
 * loaded inside the app.
 *
 * [T-telegram-in-app-safety] The values that come out of a deep link are
 * NOT part of the URL that the http branch checks. `tg://resolve?domain=X`
 * used to become `https://t.me/X` with X taken verbatim, so a value like
 * `../../evil.com` produced a URL that any normalizer turns into
 * `https://evil.com` - a link out of the app and onto a host of the
 * sender's choosing. The deep-link values are therefore validated against
 * the shapes Telegram itself uses, and anything else is refused.
 *
 * Pure function, no Android dependencies: it is pinned by unit tests.
 */
object TelegramLink {
    private const val WEB_HOST = "t.me"

    /** Telegram usernames: letters, digits and underscore, 3..32 characters. */
    private val DOMAIN_PATTERN = Regex("^[A-Za-z0-9_]{3,32}$")

    /** Invite hashes are base64url; the leading '+' of the web form is added here. */
    private val INVITE_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

    /**
     * The `https://t.me/...` form of [url], or null when [url] is not a
     * Telegram link or has no public web equivalent.
     */
    fun toWebUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        val scheme = trimmed.substringBefore(':', "").lowercase()
        return when (scheme) {
            "tg" -> fromDeepLink(trimmed)
            "http", "https" -> {
                val host = trimmed.substringAfter("://", "").substringBefore('/').lowercase()
                if (host == WEB_HOST || host.endsWith(".$WEB_HOST")) trimmed else null
            }
            else -> null
        }
    }

    /**
     * `tg://resolve?domain=channel` -> `https://t.me/channel`
     * `tg://joinchat/<hash>`        -> `https://t.me/+<hash>`
     *
     * Other deep links (for example `tg://openmessage?user_id=...`) have no
     * public web form, so they are left to the system handler.
     */
    private fun fromDeepLink(url: String): String? {
        // `tg://x` and `tg:x` are both in the wild. Cutting at the first
        // colon covers both; cutting at "://" only covered the first one.
        val afterScheme = url.substringAfter(':', "")
        val path = afterScheme.substringBefore('?').trim('/')
        val query = afterScheme.substringAfter('?', "")
        val domain = query.split('&')
            .firstOrNull { it.startsWith("domain=") }
            ?.substringAfter('=')
            ?.trim()
        if (!domain.isNullOrEmpty()) {
            // The username goes into the path of the web form, so it must
            // not be able to climb out of it.
            if (!DOMAIN_PATTERN.matches(domain)) return null
            return "https://$WEB_HOST/$domain"
        }
        if (path.startsWith("joinchat/")) {
            val hash = path.removePrefix("joinchat/").trim('/').removePrefix("+")
            if (!INVITE_PATTERN.matches(hash)) return null
            return "https://$WEB_HOST/+$hash"
        }
        return null
    }
}
