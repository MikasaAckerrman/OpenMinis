package com.openminis.app.hardening

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [test-hardening] Infra sanity: proves the Robolectric pipeline (merged
 * resources, SDK 35 simulation, JUnit runner wiring) works on the JVM before
 * any real UI-adjacent test relies on it. Not a product test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobolectricSanityTest {

    @Test
    fun `application context resolves on the JVM`() {
        val app: Context = ApplicationProvider.getApplicationContext()
        assertNotNull(app)
        assertEquals("com.openminis.app.clone", app.packageName)
    }
}
