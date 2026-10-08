package net.letsbot.chat.internal

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecureStore] backed by an AES-256-GCM key that never leaves the Android Keystore.
 *
 * Values are encrypted before they reach SharedPreferences. If the key is unavailable (for example after a backup
 * was restored to another device) values fail to decrypt and are treated as absent, so the SDK simply starts a new
 * anonymous session. Implemented directly on the Keystore because `androidx.security:security-crypto` is deprecated.
 */
internal class KeystoreSecureStore(context: Context) : SecureStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun get(key: String): String? = synchronized(lock) {
        val stored = prefs.getString(key, null) ?: return null
        try {
            decrypt(stored)
        } catch (_: GeneralSecurityException) {
            prefs.edit { remove(key) }
            null
        } catch (_: IllegalArgumentException) {
            prefs.edit { remove(key) }
            null
        }
    }

    override fun put(key: String, value: String?): Unit = synchronized(lock) {
        if (value == null) {
            prefs.edit { remove(key) }
            return
        }
        val encrypted = try {
            encrypt(value)
        } catch (_: GeneralSecurityException) {
            // Keystore unusable on this device: keep nothing rather than storing the secret in clear text.
            null
        }
        prefs.edit { if (encrypted == null) remove(key) else putString(key, encrypted) }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + sealed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLength = bytes.firstOrNull()?.toInt() ?: throw GeneralSecurityException("empty")
        if (ivLength <= 0 || bytes.size <= 1 + ivLength) throw GeneralSecurityException("malformed")
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength).toString(Charsets.UTF_8)
    }

    private companion object {
        const val PREFS_NAME = "net.letsbot.chat.secure"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "net.letsbot.chat.store.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
