package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-tg-accounts] The multi-account registry (B17 stage A): legacy
 * migration idempotency, exclusive active flag, JSON round-trip.
 */
class TelegramAccountStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = TelegramAccountStore(tmp.root)

    @Test
    fun `legacy session migrates once into an active pending account`() {
        val session = tmp.newFile("tg_session.session")
        session.writeText("fake-session-bytes")

        val migrated = store().migrateLegacyIfNeeded(session)

        assertNotNull("legacy session must register an account", migrated)
        assertEquals("pending", migrated!!.dirName)
        assertTrue("the migrated account is the active one", migrated.isActive)
        assertNull("user_id is unknown until the first get_me", migrated.userId)
        // The session file itself stays: the stage-A vault server reads the
        // classic path.
        assertTrue(session.exists())
        // Idempotent: the second run must not create a duplicate entry.
        assertNull(store().migrateLegacyIfNeeded(session))
        assertEquals(1, store().load().size)
    }

    @Test
    fun `no legacy session means no migration`() {
        assertNull(store().migrateLegacyIfNeeded(tmp.root.resolve("absent.session")))
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `accounts with existing entries do not re-migrate`() {
        val session = tmp.newFile("tg_session.session")
        store().upsert(
            TgAccount(dirName = "123456", userId = 123456, username = "me", isActive = true),
        )
        assertNull("existing accounts suppress the legacy migration", store().migrateLegacyIfNeeded(session))
        assertEquals(1, store().load().size)
        assertEquals("123456", store().load().first().dirName)
    }

    @Test
    fun `setActive is exclusive`() {
        store().upsert(TgAccount(dirName = "a", isActive = true))
        store().upsert(TgAccount(dirName = "b"))
        store().setActive("b")

        val loaded = store().load()
        assertEquals(2, loaded.size)
        assertFalse(loaded.first { it.dirName == "a" }.isActive)
        assertTrue(loaded.first { it.dirName == "b" }.isActive)
        assertEquals("b", store().findActive()!!.dirName)
    }

    @Test
    fun `json round trip preserves nullable fields`() {
        store().upsert(
            TgAccount(
                dirName = "777001",
                userId = 777001,
                phone = "+79962227579",
                username = "kurigram_user",
                firstName = "Kuri",
                isActive = true,
                addedAt = 42L,
            ),
        )
        val back = store().load().single()
        assertEquals("777001", back.dirName)
        assertEquals(777001L, back.userId)
        assertEquals("+79962227579", back.phone)
        assertEquals("kurigram_user", back.username)
        assertEquals("Kuri", back.firstName)
        assertNull(back.avatarPath)
        assertTrue(back.isActive)
        assertEquals(42L, back.addedAt)
    }

    @Test
    fun `broken registry degrades to empty instead of locking out`() {
        tmp.newFile("accounts.json").writeText("{ not valid json !!")
        assertTrue(store().load().isEmpty())
        // And the store heals it on the next save.
        store().upsert(TgAccount(dirName = "a"))
        assertEquals(1, store().load().size)
    }
}
