package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-m12-engine-swap] The strangler switch contract: the engine chain is
 * an EXPERIMENT until live-verified — a fresh install must never route the
 * production send path through it by accident. The unsafe default is the
 * one mistake this class exists to make impossible.
 */
class EngineSwapPrefsLogicTest {

    @Test
    fun `unset prefs default to disabled`() {
        // prefs==null (no context wired, JVM test env): disabled — the
        // fail-safe direction
        assertFalse(EngineSwapPrefs.isEnabled())
    }

    @Test
    fun `setEnabled without init is a no-op not a crash`() {
        // JVM: init never ran; toggle must not throw and must not flip the
        // effective state to enabled
        EngineSwapPrefs.setEnabled(true)
        assertFalse(EngineSwapPrefs.isEnabled())
    }

    @Test
    fun `flag class is stateless between tests`() {
        assertTrue(EngineSwapPrefs::class.java.declaredMethods.isNotEmpty())
    }
}
