package com.openminis.app.sandbox

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [B4] Hardware-backed AES-GCM-256 cipher for Telegram vault secrets
 * (login state: phone, verification code, api_id/api_hash — everything the
 * plaintext login-state.json used to hold on disk).
 *
 * Format: `[12-byte IV] + [ciphertext + 16-byte GCM tag]`. The IV is
 * generated fresh by the cipher on every encryption (GCM IV reuse with the
 * same key would be catastrophic — never cache it). The key never leaves
 * AndroidKeyStore: it is generated in-hardware (StrongBox-less TEE path)
 * with PURPOSE_ENCRYPT|DECRYPT, GCM block mode and no padding.
 *
 * The AndroidKeyStore plumbing is NOT unit-testable on the JVM (Robolectric
 * has no AndroidKeyStore provider), so the mechanical core
 * [encryptWith]/[decryptWith] takes a [SecretKey] and is exercised by JVM
 * tests against a software AES key; the keystore path is thin wiring.
 */
object VaultKeystoreCipher {

    private const val KEY_ALIAS = "minis_tg_vault_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12
    private const val TAG_BITS = 128

    /** The keystore-resident key, created on first use. */
    fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** `encrypt(plainBytes) = [12-byte IV] + [ciphertext + GCM tag]`. */
    fun encrypt(plainBytes: ByteArray): ByteArray = encryptWith(getOrCreateKey(), plainBytes)

    /**
     * `null` on ANY failure (truncated input, corrupted tag, keystore
     * hiccup) — a broken secret must degrade to "no state", never crash the
     * process on a background read.
     */
    fun decrypt(encryptedBytes: ByteArray): ByteArray? = decryptWith(getOrCreateKey(), encryptedBytes)

    internal fun encryptWith(key: SecretKey, plainBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_SIZE_BYTES) { "unexpected GCM IV size ${iv.size}" }
        val body = cipher.doFinal(plainBytes)
        return iv + body
    }

    internal fun decryptWith(key: SecretKey, encryptedBytes: ByteArray): ByteArray? = try {
        require(encryptedBytes.size > IV_SIZE_BYTES) { "ciphertext too short" }
        val iv = encryptedBytes.copyOfRange(0, IV_SIZE_BYTES)
        val body = encryptedBytes.copyOfRange(IV_SIZE_BYTES, encryptedBytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.doFinal(body)
    } catch (_: Exception) {
        // AEADBadTagException (tampered/corrupted), truncated input, provider
        // hiccups — all degrade to null; the caller re-derives the state.
        null
    }
}
