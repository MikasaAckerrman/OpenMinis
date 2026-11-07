package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [B17-C] The account card shows the phone number masked: country prefix +
 * last four digits, everything between masked. A leaked screenshot must
 * not hand out the full number.
 */
class TelegramPhoneMaskTest {

    @Test
    fun `a full number keeps only the prefix and the last four digits`() {
        assertEquals("+7 *** ***-4567", maskTgPhone("+79991234567"))
    }

    @Test
    fun `spaces and dashes in the input do not change the shape`() {
        assertEquals("+7 *** ***-4567", maskTgPhone("+7 999 123-45-67"))
    }

    @Test
    fun `a short number degrades to a full mask`() {
        assertEquals("***", maskTgPhone("+1234"))
        assertEquals("***", maskTgPhone("12345"))
    }

    @Test
    fun `blank input is a full mask`() {
        assertEquals("***", maskTgPhone(""))
    }
}
