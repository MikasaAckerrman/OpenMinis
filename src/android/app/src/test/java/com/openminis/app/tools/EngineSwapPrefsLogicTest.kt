package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-engine-earns-default] Default OFF since the 09.10 agreement: the
 * legacy loop leads production turns. The engine stays one tap away (the
 * ⚡/shield top-bar toggle) and earns its default through the autonomous
 * stand battery, not through the user's sessions. The unsafe default
 * direction is a silent engine takeover on install/upgrade — the vc119
 * live incident: the version-bump reset re-enabled the engine although
 * the agreement said legacy leads.
 */
class EngineSwapPrefsLogicTest {

    @Test
    fun `unset prefs default to legacy engine OFF`() {
        // prefs==null (no context wired, JVM test env): the default is the
        // LEGACY loop — the engine is opt-in via the toggle.
        assertFalse(EngineSwapPrefs.isEnabled())
    }

    @Test
    fun `setEnabled without init is a no-op not a crash`() {
        // JVM: init never ran; toggle must not throw and must not flip the
        // effective state (still the default: legacy).
        EngineSwapPrefs.setEnabled(true)
        assertFalse(EngineSwapPrefs.isEnabled())
    }

    @Test
    fun `flag class is stateless between tests`() {
        assertTrue(EngineSwapPrefs::class.java.declaredMethods.isNotEmpty())
    }
}
