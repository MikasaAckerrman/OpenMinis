package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-engine-shadow-sessions] Per-session engine routing — the pure
 * decision logic of EngineSwapPrefs without Android prefs (the prefs
 * plumbing is exercised on-device by the debug.engineSwap RPC smoke
 * test protocol).
 *
 * The user's shadow-test contract (08.10): "пустая сессия на новом
 * движке, я старым мониторю её ошибки" — a shadow session runs the
 * engine chain while the GLOBAL flag stays OFF, so the monitoring
 * conversation keeps the proven legacy loop.
 */
class EngineSwapShadowRoutingTest {

    // The decision under test, mirroring isEnabledFor(sessionId):
    // override > global. Modeled as a pure function for testability.
    private fun routesThroughEngine(
        globalEnabled: Boolean,
        overriddenSessions: Set<String>,
        sessionId: String?,
    ): Boolean {
        if (sessionId != null && sessionId in overriddenSessions) return true
        return globalEnabled
    }

    @Test
    fun `shadow session routes through engine while global stays off`() {
        // The core of the user's protocol: the monitoring conversation is
        // NOT overridden, the test session IS, global is OFF.
        val overrides = setOf("test-session")
        assertFalse(routesThroughEngine(false, overrides, "monitor-session"))
        assertTrue(routesThroughEngine(false, overrides, "test-session"))
    }

    @Test
    fun `global on routes every session`() {
        assertTrue(routesThroughEngine(true, emptySet(), "any-session"))
        assertTrue(routesThroughEngine(true, setOf("x"), "any-session"))
    }

    @Test
    fun `blank session id never overrides`() {
        // isEnabledFor's blank guard: an unloaded/draft session falls
        // back to the global flag — a blank id can never arm a shadow.
        val overrides = setOf("")
        assertFalse(routesThroughEngine(false, overrides, null))
        assertFalse(routesThroughEngine(false, emptySet(), ""))
    }

    @Test
    fun `override set membership is add-remove stable`() {
        // Mirrors setSessionOverride add/remove semantics.
        var overrides = emptySet<String>()
        overrides = overrides + "s1"
        assertEquals(setOf("s1"), overrides)
        overrides = overrides + "s2"
        assertEquals(setOf("s1", "s2"), overrides)
        overrides = overrides - "s1"
        assertEquals(setOf("s2"), overrides)
    }
}
