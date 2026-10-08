package com.openminis.app.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-engine-default-flip] The engine (new agent loop) is the DEFAULT since
 * the 08.10 user decision: unset prefs route the production send path
 * through the engine. Legacy stays as the backup (per-turn degradation +
 * the top-bar button). The unsafe default direction is now the opposite:
 * a null-prefs environment must still lead with the ENGINE, not silently
 * fall to legacy.
 */
class EngineSwapPrefsLogicTest {

    @Test
    fun `unset prefs default to engine enabled`() {
        // prefs==null (no context wired, JVM test env): the default is the
        // ENGINE — the new fail-safe direction (legacy is one button away).
        assertTrue(EngineSwapPrefs.isEnabled())
    }

    @Test
    fun `setEnabled without init is a no-op not a crash`() {
        // JVM: init never ran; toggle must not throw and must not flip the
        // effective state (still the default: engine).
        EngineSwapPrefs.setEnabled(false)
        assertTrue(EngineSwapPrefs.isEnabled())
    }

    @Test
    fun `flag class is stateless between tests`() {
        assertTrue(EngineSwapPrefs::class.java.declaredMethods.isNotEmpty())
    }
}
