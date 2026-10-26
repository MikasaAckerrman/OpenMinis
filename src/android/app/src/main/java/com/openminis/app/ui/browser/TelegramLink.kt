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
 * Pure function, no Android dependencies: it is pinned by unit tests.
 */
object TelegramLink {
    private const val WEB_HOST = "t.me"

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
        val afterScheme = url.substringAfter("://", "")
        val path = afterScheme.substringBefore('?')
        val query = afterScheme.substringAfter('?', "")
        val domain = query.split('&')
            .firstOrNull { it.startsWith("domain=") }
            ?.substringAfter('=')
            ?.trim()
        if (!domain.isNullOrEmpty()) return "https://$WEB_HOST/$domain"
        if (path.startsWith("joinchat/")) {
            val hash = path.removePrefix("joinchat/").trim('/').removePrefix("+")
            if (hash.isNotEmpty()) return "https://$WEB_HOST/+$hash"
        }
        return null
    }
}
