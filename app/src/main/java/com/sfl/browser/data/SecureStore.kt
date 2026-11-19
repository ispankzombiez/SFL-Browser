package com.sfl.browser.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Community API key in Android Keystore-backed AES-GCM so the key never sits
 * in plaintext preferences and is never compiled into the APK.
 *
 * The key is per-device: wiping the app (or clearing its data) requires re-entering it.
 */
object SecureStore {
    private const val ALIAS = "sfl_secure_store"
    private const val PREFS = "sfl_secure"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun put(context: Context, value: String?) {
        val editor = prefs(context).edit()
        if (value.isNullOrEmpty()) {
            editor.remove("v").apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(IV_LENGTH_BYTES + ciphertext.size)
        iv.copyInto(blob, 0)
        ciphertext.copyInto(blob, IV_LENGTH_BYTES)
        editor.putString("v", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(context: Context): String? {
        val stored = prefs(context).getString("v", null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = blob.copyOfRange(IV_LENGTH_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            // Keystore entry lost or blob corrupted — treat as "no key set".
            null
        }
    }
}
