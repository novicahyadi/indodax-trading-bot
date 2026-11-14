package com.indodaxbot.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.indodaxbot.api.Credentials
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the API key and secret encrypted with an AES-256-GCM key that lives in the
 * Android Keystore (the key material never leaves secure hardware/OS storage).
 * Only ciphertext is written to SharedPreferences.
 */
class CredentialStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("indodax_credentials", Context.MODE_PRIVATE)

    fun save(apiKey: String, apiSecret: String) {
        prefs.edit()
            .putString(K_KEY, encrypt(apiKey.trim()))
            .putString(K_SECRET, encrypt(apiSecret.trim()))
            .apply()
    }

    fun load(): Credentials? {
        val k = prefs.getString(K_KEY, null) ?: return null
        val s = prefs.getString(K_SECRET, null) ?: return null
        return try {
            Credentials(decrypt(k), decrypt(s))
        } catch (e: Exception) {
            null // key lost or data corrupted: treat as "not set"
        }
    }

    fun hasCredentials(): Boolean = load() != null

    fun clear() {
        prefs.edit().remove(K_KEY).remove(K_SECRET).apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, IV_LEN)
        val ct = all.copyOfRange(IV_LEN, all.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "indodax_bot_credentials_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val K_KEY = "k"
        const val K_SECRET = "s"
    }
}
