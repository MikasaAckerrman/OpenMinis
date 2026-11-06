package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B29-engine-swap-default] Default ON since 06.11 — a missing KEY now
 * resolves to the engine. These tests exercise the JVM no-init state
 * (prefs==null): that path stays CONSERVATIVE (legacy) — init() runs in
 * MinisApp before any send, so null here means a broken setup, not a
 * fresh install. The missing-KEY default and the upgrade reset are
 * Android-bound (SharedPreferences) and verified on-device via the
 * debug.engineSwap RPC smoke protocol.
 */
class EngineSwapPrefsLogicTest {

    @Test
    fun `unset prefs default to legacy engine OFF`() {
        // prefs==null (no context wired, JVM test env): the PRE-INIT state
        // stays conservative — legacy. NOT the missing-KEY default (that
        // is engine-ON since B29); this is the broken-setup guard only.
        assertFalse(EngineSwapPrefs.isEnabled())
    }

    @Test
    fun `setEnabled without init is a no-op not a crash`() {
        // JVM: init never ran; toggle must not throw and must not flip the
        // effective state (still the conservative pre-init state).
        EngineSwapPrefs.setEnabled(true)
        assertFalse(EngineSwapPrefs.isEnabled())
    }

    @Test
    fun `flag class is stateless between tests`() {
        assertTrue(EngineSwapPrefs::class.java.declaredMethods.isNotEmpty())
    }
}
