package com.openminis.app.sandbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

/**
 * [B4] The AES-GCM mechanics of the vault cipher. Runs against a SOFTWARE
 * AES-256 key on the JVM: the AndroidKeyStore plumbing
 * ([VaultKeystoreCipher.getOrCreateKey]) is a thin seam Robolectric cannot
 * exercise (no AndroidKeyStore provider there), so the tests pin the
 * format `[12-byte IV] + [ciphertext + 16-byte tag]`, the fresh-IV rule
 * and the never-crash decryption contract that the production wiring
 * inherits.
 */
class VaultKeystoreCipherTest {

    private val key: javax.crypto.SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `roundtrip restores the plaintext`() {
        val plain = """{"phone":"+79991234567","code":"48213","api_hash":"secret"}"""
            .encodeToByteArray()
        val encrypted = VaultKeystoreCipher.encryptWith(key, plain)
        assertEquals(12 + plain.size + 16, encrypted.size)
        val decrypted = VaultKeystoreCipher.decryptWith(key, encrypted)
        assertNotNull(decrypted)
        assertArrayEquals(plain, decrypted!!)
    }

    @Test
    fun `two encryptions of the same text differ (fresh IV per call)`() {
        val plain = "the same telegram credentials".encodeToByteArray()
        val a = VaultKeystoreCipher.encryptWith(key, plain)
        val b = VaultKeystoreCipher.encryptWith(key, plain)
        assertFalse("IV reuse would be catastrophic in GCM", a.contentEquals(b))
        // And both decrypt back to the same plaintext.
        assertArrayEquals(plain, VaultKeystoreCipher.decryptWith(key, a)!!)
        assertArrayEquals(plain, VaultKeystoreCipher.decryptWith(key, b)!!)
    }

    @Test
    fun `a corrupted tag degrades to null instead of AEADBadTagException`() {
        val encrypted = VaultKeystoreCipher.encryptWith(key, "phone".encodeToByteArray())
        encrypted[encrypted.size - 1] = (encrypted.last().toInt() xor 0x41).toByte()
        assertNull(VaultKeystoreCipher.decryptWith(key, encrypted))
    }

    @Test
    fun `truncated and empty inputs degrade to null`() {
        assertNull(VaultKeystoreCipher.decryptWith(key, ByteArray(4)))
        assertNull(VaultKeystoreCipher.decryptWith(key, ByteArray(0)))
    }

    @Test
    fun `wrong key degrades to null (cross-key reads are refused)`() {
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypted = VaultKeystoreCipher.encryptWith(key, "secret".encodeToByteArray())
        assertNull(VaultKeystoreCipher.decryptWith(otherKey, encrypted))
    }

    @Test
    fun `binary content survives the roundtrip`() {
        val plain = ByteArray(64) { (it * 37).toByte() }
        val decrypted = VaultKeystoreCipher.decryptWith(key, VaultKeystoreCipher.encryptWith(key, plain))
        assertTrue(decrypted!!.contentEquals(plain))
    }
}
