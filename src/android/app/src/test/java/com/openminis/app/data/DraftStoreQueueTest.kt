package com.openminis.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [test-hardening] Scenario B (store level): the persisted queue must survive
 * a "process death" (fresh context) exactly as written, and clearQueue must
 * leave nothing behind. SharedPreferences semantics on Robolectric mirror the
 * on-device store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DraftStoreQueueTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val sid = "queue-roundtrip-session"

    @Test
    fun `queue json survives a fresh context (process death simulation)`() {
        val json = """[{"id":"queued_1","text":"hello"},{"id":"queued_2","text":"second"}]"""
        DraftStore.saveQueue(ctx, sid, json)
        assertEquals("the persisted JSON must round-trip byte-exact", json, DraftStore.loadQueue(ctx, sid))
        DraftStore.clearQueue(ctx, sid)
        assertEquals("after clearQueue nothing may remain", "", DraftStore.loadQueue(ctx, sid))
    }

    @Test
    fun `empty and blank queues clear the persisted entry`() {
        DraftStore.saveQueue(ctx, sid, """[{"id":"q","text":"t"}]""")
        DraftStore.saveQueue(ctx, sid, "[]")
        assertEquals("an empty queue payload must remove the entry", "", DraftStore.loadQueue(ctx, sid))
    }

    @Test
    fun `sessions are isolated`() {
        DraftStore.saveQueue(ctx, "sidA", """[{"id":"a1","text":"a"}]""")
        DraftStore.saveQueue(ctx, "sidB", """[{"id":"b1","text":"b"}]""")
        assertTrue(DraftStore.loadQueue(ctx, "sidA").contains("a1"))
        assertTrue(DraftStore.loadQueue(ctx, "sidB").contains("b1"))
        DraftStore.clearQueue(ctx, "sidA")
        assertEquals("", DraftStore.loadQueue(ctx, "sidA"))
        assertTrue("clearing sidA must not touch sidB", DraftStore.loadQueue(ctx, "sidB").contains("b1"))
        DraftStore.clearQueue(ctx, "sidB")
    }
}
