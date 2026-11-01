package com.openminis.app.engine

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [B20 Thermal & RAM Guard] The clamp decision core, pinned. Constants are
 * compile-time inlined from android.os.PowerManager, so this runs headless.
 */
class DeviceGuardTest {

    @Test
    fun `normal device keeps full parallelism`() {
        assertEquals(1, DeviceGuard.dividerFor(PowerManager.THERMAL_STATUS_NONE, lowRam = false))
        assertEquals(1, DeviceGuard.dividerFor(PowerManager.THERMAL_STATUS_LIGHT, lowRam = false))
    }

    @Test
    fun `moderate heat halves the ceilings`() {
        assertEquals(2, DeviceGuard.dividerFor(PowerManager.THERMAL_STATUS_MODERATE, lowRam = false))
    }

    @Test
    fun `severe heat clamps hard`() {
        assertEquals(3, DeviceGuard.dividerFor(PowerManager.THERMAL_STATUS_SEVERE, lowRam = false))
    }

    @Test
    fun `emergency heat clamps to a single slot`() {
        assertEquals(6, DeviceGuard.dividerFor(PowerManager.THERMAL_STATUS_EMERGENCY, lowRam = false))
    }

    @Test
    fun `low memory doubles the divider`() {
        assertEquals(2, DeviceGuard.dividerFor(PowerManager.THERMAL_STATUS_NONE, lowRam = true))
        assertEquals(6, DeviceGuard.dividerFor(PowerManager.THERMAL_STATUS_EMERGENCY, lowRam = true))
    }

    @Test
    fun `the clamp never kills the last slot`() {
        // Any status x any RAM combination stays inside 1..6: parallelism
        // shrinks, it never reaches zero.
        for (status in 0..6) {
            for (lowRam in listOf(false, true)) {
                val d = DeviceGuard.dividerFor(status, lowRam)
                assert(d in 1..6) { "divider $d out of range for status=$status lowRam=$lowRam" }
            }
        }
    }
}
