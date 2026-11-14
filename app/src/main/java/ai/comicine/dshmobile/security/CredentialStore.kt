package ai.comicine.dshmobile.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("secure_credentials", Context.MODE_PRIVATE)
    private val alias = "deepseek-harness-credential-key-v1"

    @Synchronized
    fun getApiKey(): String? {
        val encoded = preferences.getString("provider.deepseek.apiKey", null) ?: return null
        val parts = encoded.split(':', limit = 2)
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    @Synchronized
    fun setApiKey(value: String) {
        if (value.isBlank()) {
            preferences.edit().remove("provider.deepseek.apiKey").apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
        preferences.edit().putString("provider.deepseek.apiKey", encoded).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }
}
