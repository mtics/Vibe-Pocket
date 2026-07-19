package au.edu.uts.vibepocket.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface Store {
    fun save(config: Config)
    fun load(): Config?
    fun clear()
}

class Vault(context: Context) : Store {
    private val preferences = context.getSharedPreferences("vibe_pocket", Context.MODE_PRIVATE)

    override fun save(config: Config) {
        val payload = JSONObject()
            .put("baseUrl", config.normalizedUrl)
            .put("token", config.credential)
            .toString()
        check(preferences.edit().putString(CONFIG_KEY, encrypt(payload)).commit()) {
            "Vibe Pocket could not save the Bridge configuration."
        }
    }

    override fun load(): Config? {
        val encrypted = preferences.getString(CONFIG_KEY, null) ?: return null
        return runCatching {
            val payload = JSONObject(decrypt(encrypted))
            Config(
                baseUrl = payload.getString("baseUrl"),
                credential = payload.getString("token"),
            )
        }.getOrNull()
    }

    override fun clear() {
        check(preferences.edit().remove(CONFIG_KEY).commit()) {
            "Vibe Pocket could not clear the Bridge configuration."
        }
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val parts = payload.split(":", limit = 2)
        require(parts.size == 2) { "Malformed Vibe Pocket configuration." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val CONFIG_KEY = "encrypted_connection"
        const val KEY_ALIAS = "vibe-pocket-connection"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
