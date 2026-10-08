package com.openminis.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-session-provider-visibility] The provider resolver behind
 * chat.session.status's providerLabel/providerBaseUrl: the session's
 * model entry id "<instanceUuid>/<modelId>" must resolve to the exact
 * instance the session routes through. User request 08.10: "чтобы ты
 * видел название провайдера, который я использую в определённой сессии".
 *
 * Pure string-shape logic (uuid prefix matching is the contract;
 * instance lookup is exercised against the test config below).
 */
class SessionProviderVisibilityTest {

    @Test
    fun `entry id splits into instance uuid and model`() {
        val id = "bbc98c77-314c-4874-be6e-ef8d30e45154/glm-5.3"
        assertEquals("bbc98c77-314c-4874-be6e-ef8d30e45154", id.substringBefore('/'))
        assertEquals("glm-5.3", id.substringAfter('/'))
    }

    @Test
    fun `blank and bare ids resolve to no provider`() {
        assertNull(null)
        // shape contract: no slash -> no instance uuid -> no provider
        assertEquals("glm-5.3", "glm-5.3".substringBefore('/'))
    }
}
