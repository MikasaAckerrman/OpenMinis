package com.openminis.app.service

/**
 * [B27-B] Pure policy for the FGS restart path.
 *
 * Under START_STICKY the system resurrects the service with a NULL intent
 * after a process kill. If no agent session is actually running, starting
 * foreground there resurrects a ZOMBIE service: a permanent "Minis" in the
 * notification shade, battery drain, and OEM batteryoptimisation pressure
 * against the app — the shape behind the "guaranteed crash in the
 * background" reports (the zombie also re-enters tool ticks with stale
 * state). The rule: a null-intent restart with zero active sessions stops
 * the service; any real start brings it back with a fresh explicit intent.
 * Kept pure so the decision is unit-testable without booting the service.
 */
object AgentFgsRules {

    fun shouldStopOnZombieRestart(hasIntent: Boolean, activeSessionCount: Int): Boolean =
        !hasIntent && activeSessionCount <= 0
}
