package com.tawny.monitor.nativeapp

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small secrets (the channel key, the Nostr secret) under an AES-256
 * key that lives in the Android Keystore and never leaves it. The Keystore
 * has no secp256k1, so the Nostr key itself can't live there; wrapping it is
 * the next best thing: a copy of the app's files alone doesn't reveal it.
 *
 * Format: base64(12-byte IV ‖ AES-GCM ciphertext+tag), prefixed `v1:`.
 */
object SecretBox {
    private const val ALIAS = "tawny_native_secrets"
    private const val PREFIX = "v1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** The secret, or null if [sealed] isn't ours or doesn't open (never a guess). */
    fun open(sealed: String): String? {
        if (!sealed.startsWith(PREFIX)) return null
        return runCatching {
            val raw = Base64.decode(sealed.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size < 12 + 16) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    fun isSealed(v: String) = v.startsWith(PREFIX)
}
