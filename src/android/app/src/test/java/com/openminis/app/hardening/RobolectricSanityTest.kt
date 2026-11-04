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
 * application = plain Application: the manifest's MinisApp runs heavy onCreate
 * (crash-capture dirs, deep-link registration) that needs a real device env —
 * a JVM sanity gate must not depend on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RobolectricSanityTest {

    @Test
    fun `application context resolves on the JVM`() {
        val app: Context = ApplicationProvider.getApplicationContext()
        assertNotNull(app)
        assertEquals("com.openminis.app.clone", app.packageName)
    }
}
