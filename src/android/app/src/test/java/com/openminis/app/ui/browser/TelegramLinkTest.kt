package com.openminis.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-telegram-in-app] A Telegram link has to stay inside the app, so the
 * deep link is rewritten to its public web form.
 */
class TelegramLinkTest {

    @Test
    fun `a resolve deep link becomes the web form`() {
        assertEquals(
            "https://t.me/durov",
            TelegramLink.toWebUrl("tg://resolve?domain=durov"),
        )
    }

    @Test
    fun `an invite link becomes the web form`() {
        assertEquals(
            "https://t.me/+AbCdEf",
            TelegramLink.toWebUrl("tg://joinchat/AbCdEf"),
        )
    }

    @Test
    fun `a web link is kept as it is`() {
        assertEquals("https://t.me/durov", TelegramLink.toWebUrl("https://t.me/durov"))
        assertEquals("https://t.me/durov/123", TelegramLink.toWebUrl("https://t.me/durov/123"))
    }

    @Test
    fun `a foreign link is not a telegram link`() {
        assertNull(TelegramLink.toWebUrl("https://example.com/t.me"))
        assertNull(TelegramLink.toWebUrl("https://nott.me/durov"))
        assertNull(TelegramLink.toWebUrl("mailto:a@b.c"))
        assertNull(TelegramLink.toWebUrl(null))
        assertNull(TelegramLink.toWebUrl(""))
    }

    @Test
    fun `a deep link without a web form is left alone`() {
        assertNull(TelegramLink.toWebUrl("tg://openmessage?user_id=123"))
    }

    // [T-telegram-in-app-safety] The values of a deep link must not be able
    // to leave t.me: a path with ".." is normalised away by the browser and
    // the host would change.

    @Test
    fun `a domain that climbs out of the path is refused`() {
        assertNull(TelegramLink.toWebUrl("tg://resolve?domain=../../evil.com"))
        assertNull(TelegramLink.toWebUrl("tg://resolve?domain=..%2F..%2Fevil.com"))
        assertNull(TelegramLink.toWebUrl("tg://resolve?domain=/evil.com"))
        assertNull(TelegramLink.toWebUrl("tg://resolve?domain=evil.com/x"))
    }

    @Test
    fun `a domain that is not a telegram username is refused`() {
        assertNull(TelegramLink.toWebUrl("tg://resolve?domain=ab"))
        assertNull(TelegramLink.toWebUrl("tg://resolve?domain=durov!"))
        assertNull(TelegramLink.toWebUrl("tg://resolve?domain=du rov"))
    }

    @Test
    fun `an invite hash that is not base64url is refused`() {
        assertNull(TelegramLink.toWebUrl("tg://joinchat/../../evil"))
        assertNull(TelegramLink.toWebUrl("tg://joinchat/a b"))
    }

    @Test
    fun `the deep link works with and without slashes`() {
        assertEquals(
            "https://t.me/durov",
            TelegramLink.toWebUrl("tg:resolve?domain=durov"),
        )
    }
}
