package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-swap-flag-cross-build] A debug-RPC engine-swap experiment must not
 * survive an app update: SharedPreferences persist across reinstalls, and
 * a stale enabled=true (armed while the engine path carried the vc98-era
 * user-text annihilation) silently routed the vc99/vc100 installs through
 * the broken chain — "the new build regressed" with nobody having flipped
 * anything. The version stamp defuses the landmine: any cross-build state
 * resets to the default OFF.
 *
 * Pure-logic half of EngineSwapPrefs (SharedPreferences wiring itself is
 * thin and Android-bound; the decision is what needed freezing).
 */
class EngineSwapPrefsUpgradeResetTest {

    @Test
    fun `same build keeps the armed flag`() {
        assertFalse(EngineSwapPrefs.mustResetOnUpgrade(100, 100))
    }

    @Test
    fun `new build resets the flag`() {
        assertTrue(EngineSwapPrefs.mustResetOnUpgrade(98, 99))
        assertTrue(EngineSwapPrefs.mustResetOnUpgrade(99, 100))
        // Upgrade several builds later — still resets.
        assertTrue(EngineSwapPrefs.mustResetOnUpgrade(98, 130))
    }

    @Test
    fun `downgrade resets the flag too`() {
        // Rollback to an older APK (the user does this under stress):
        // the flag must not survive in either direction.
        assertTrue(EngineSwapPrefs.mustResetOnUpgrade(100, 81))
    }

    @Test
    fun `never-stamped prefs reset — defuses the vc98 landmine`() {
        // Pre-stamp install: only KEY=enabled exists, no version stamp.
        assertTrue(EngineSwapPrefs.mustResetOnUpgrade(Int.MIN_VALUE, 99))
    }
}
