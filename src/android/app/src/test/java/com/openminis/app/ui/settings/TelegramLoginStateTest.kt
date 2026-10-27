package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramLoginStateTest {

    @Test
    fun `state survives a json round trip`() {
        val original = TelegramLoginState(
            phone = "+79991234567",
            code = "48213",
            apiId = "123456",
            apiHash = "0123456789abcdef0123456789abcdef",
            codeSent = true,
            needsPassword = false,
        )
        val restored = TelegramLoginState.fromJson(original.toJson().toString())
        assertEquals(original, restored)
    }

    @Test
    fun `the verification code is kept`() {
        val state = TelegramLoginState(phone = "+79991234567", code = "48213", codeSent = true)
        val restored = TelegramLoginState.fromJson(state.toJson().toString())
        assertEquals("48213", restored.code)
        assertTrue(restored.codeSent)
    }

    @Test
    fun `the cloud password step is kept`() {
        val state = TelegramLoginState(phone = "+79991234567", needsPassword = true)
        val restored = TelegramLoginState.fromJson(state.toJson().toString())
        assertTrue(restored.needsPassword)
    }

    @Test
    fun `a broken file means no state instead of a crash`() {
        assertEquals(TelegramLoginState(), TelegramLoginState.fromJson("{not json"))
        assertEquals(TelegramLoginState(), TelegramLoginState.fromJson(""))
        assertEquals(TelegramLoginState(), TelegramLoginState.fromJson(null))
    }

    @Test
    fun `an empty state asks to be cleared`() {
        assertTrue(TelegramLoginState().isEmpty)
        assertFalse(TelegramLoginState(phone = "+79991234567").isEmpty)
    }
}
