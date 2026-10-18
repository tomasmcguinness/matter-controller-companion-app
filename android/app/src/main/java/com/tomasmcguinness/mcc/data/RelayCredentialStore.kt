package com.tomasmcguinness.mcc.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps each controller's push relay password, keyed by controller id.
 *
 * The username lives on [Controller] alongside the rest of its metadata; only the password is
 * secret enough to encrypt. It's sealed with an AES key that never leaves the Android Keystore.
 */
object RelayCredentialStore {

    private const val KEY_ALIAS = "relay-credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("relay_credentials", Context.MODE_PRIVATE)
    }

    fun password(controllerId: String): String? {
        val sealed = prefs.getString(controllerId, null) ?: return null

        return try {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
        } catch (e: Exception) {
            // The key went away (e.g. a backup restored onto another phone); the password is lost.
            null
        }
    }

    /** Saves the password, or removes it when [password] is null or empty. */
    fun setPassword(password: String?, controllerId: String) {
        if (password.isNullOrEmpty()) {
            removePassword(controllerId)
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))

        prefs.edit { putString(controllerId, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
    }

    fun removePassword(controllerId: String) {
        prefs.edit { remove(controllerId) }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )

        return generator.generateKey()
    }
}
