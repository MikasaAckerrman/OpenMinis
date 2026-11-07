package com.openminis.app.ui.settings

import com.openminis.app.sandbox.VaultKeystoreCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/**
 * [B4] The encrypted login-state store: saves to `login-state.enc`
 * (AES-GCM through injected software-key lambdas — the production default
 * resolves AndroidKeyStore, which the JVM cannot exercise), migrates the
 * plaintext legacy file away, and degrades a corrupted ciphertext to "no
 * state" instead of crashing the sign-in screen.
 */
class TelegramLoginStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val state = TelegramLoginState(
        phone = "+79991234567",
        code = "48213",
        apiId = "123456",
        apiHash = "0123456789abcdef0123456789abcdef",
        codeSent = true,
    )

    private val key: javax.crypto.SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store(encFile: java.io.File) = TelegramLoginStateStore(
        file = encFile,
        encrypt = { VaultKeystoreCipher.encryptWith(key, it) },
        decrypt = { VaultKeystoreCipher.decryptWith(key, it) },
    )

    @Test
    fun `save writes an encrypted enc file and load restores the state`() {
        val enc = tmp.newFile("login-state.enc")
        val store = store(enc)
        store.save(state)
        assertTrue(enc.exists())
        // The file on disk is NOT the plaintext JSON.
        val raw = enc.readBytes()
        assertFalse("plaintext on disk would defeat the whole fix", raw.toString(Charsets.UTF_8).contains("+79991234567"))
        assertEquals(state, store.load())
    }

    @Test
    fun `legacy plaintext json is migrated to enc and deleted`() {
        val vault = tmp.newFolder("tg-vault")
        val legacy = java.io.File(vault, "login-state.json")
        legacy.writeText(state.toJson().toString())
        val enc = java.io.File(vault, "login-state.enc")

        val store = store(enc)
        store.migrateLegacyIfNeeded(legacy)

        assertFalse("plaintext must not survive the migration", legacy.exists())
        assertTrue(enc.exists())
        assertEquals(state, store.load())
    }

    @Test
    fun `migration is idempotent (no legacy file is a no-op)`() {
        val store = store(tmp.newFile("login-state.enc"))
        store.migrateLegacyIfNeeded(java.io.File(tmp.root, "missing.json"))
        assertTrue(store.load().isEmpty)
    }

    @Test
    fun `a corrupted enc file degrades to no state`() {
        val enc = tmp.newFile("login-state.enc")
        enc.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14))
        assertEquals(TelegramLoginState(), store(enc).load())
    }

    @Test
    fun `an empty state clears the enc file`() {
        val enc = tmp.newFile("login-state.enc")
        val store = store(enc)
        store.save(state)
        assertNotNull(enc.takeIf { it.exists() })
        store.save(TelegramLoginState())
        assertFalse("a finished sign-in leaves nothing behind", enc.exists())
    }
}
