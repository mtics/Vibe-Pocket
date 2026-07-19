package au.edu.uts.vibepocket.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
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
    fun saveClaim(claim: Claim)
    fun loadClaim(): Claim?
    fun clearClaim()
    fun saveRevocation(config: Config)
    fun loadRevocation(): Config?
    fun clearRevocation()
    fun enqueueRevocation(config: Config) = saveRevocation(config)
    fun loadRevocations(): List<Config> = listOfNotNull(loadRevocation())
    fun removeRevocation(config: Config): Boolean {
        if (loadRevocation() != config) return false
        clearRevocation()
        return true
    }
    fun saveVoiceStop(stop: VoiceStop)
    fun loadVoiceStop(): VoiceStop?
    fun clearVoiceStop(idempotencyKey: String): Boolean

    fun commit(config: Config) {
        save(config)
        clearClaim()
    }

    fun forget(config: Config) {
        enqueueRevocation(config)
        clear()
        clearClaim()
    }
}

data class Claim(
    val invitation: Invitation,
    val nonce: String,
)

data class VoiceStop(
    val config: Config,
    val idempotencyKey: String,
) {
    init {
        require(idempotencyKey.length in 1..160 && idempotencyKey.none(Char::isISOControl)) {
            "The voice stop idempotency key is invalid."
        }
    }
}

class Vault(context: Context) : Store {
    private val preferences = context.getSharedPreferences("vibe_pocket", Context.MODE_PRIVATE)

    override fun save(config: Config) {
        check(preferences.edit().putString(CONFIG_KEY, encrypt(config.encode())).commit()) {
            "Vibe Pocket could not save the Bridge configuration."
        }
    }

    override fun load(): Config? {
        val encrypted = preferences.getString(CONFIG_KEY, null) ?: return null
        return runCatching {
            decodeConfig(decrypt(encrypted))
        }.getOrNull()
    }

    override fun clear() {
        check(preferences.edit().remove(CONFIG_KEY).commit()) {
            "Vibe Pocket could not clear the Bridge configuration."
        }
    }

    override fun saveClaim(claim: Claim) {
        val payload = JSONObject()
            .put("origin", claim.invitation.origin)
            .put("code", claim.invitation.code)
            .put("nonce", claim.nonce)
            .toString()
        check(preferences.edit().putString(CLAIM_KEY, encrypt(payload)).commit()) {
            "Vibe Pocket could not save the pending pairing claim."
        }
    }

    override fun loadClaim(): Claim? {
        val encrypted = preferences.getString(CLAIM_KEY, null) ?: return null
        return runCatching {
            val payload = JSONObject(decrypt(encrypted))
            Claim(
                invitation = Invitation(payload.getString("origin"), payload.getString("code")),
                nonce = payload.getString("nonce"),
            )
        }.getOrNull()
    }

    override fun clearClaim() {
        check(preferences.edit().remove(CLAIM_KEY).commit()) {
            "Vibe Pocket could not clear the pending pairing claim."
        }
    }

    @Synchronized
    override fun saveRevocation(config: Config) {
        persistRevocations(listOf(config))
    }

    @Synchronized
    override fun enqueueRevocation(config: Config) {
        val queued = loadRevocations()
        if (queued.any { it.matches(config) }) return
        persistRevocations(queued + config)
    }

    @Synchronized
    override fun loadRevocations(): List<Config> {
        val encrypted = preferences.getString(REVOCATION_KEY, null) ?: return emptyList()
        return runCatching { decodeRevocations(decrypt(encrypted)) }.getOrElse {
            throw IllegalStateException("Vibe Pocket could not read pending credential revocations.", it)
        }
    }

    @Synchronized
    override fun removeRevocation(config: Config): Boolean {
        val queued = loadRevocations()
        if (queued.none { it.matches(config) }) return false
        persistRevocations(queued.filterNot { it.matches(config) })
        return true
    }

    private fun persistRevocations(configs: List<Config>) {
        val edit = preferences.edit()
        if (configs.isEmpty()) {
            edit.remove(REVOCATION_KEY)
        } else {
            edit.putString(REVOCATION_KEY, encrypt(encodeRevocations(configs)))
        }
        check(edit.commit()) {
            "Vibe Pocket could not save the pending credential revocation."
        }
    }

    @Synchronized
    override fun loadRevocation(): Config? = loadRevocations().firstOrNull()

    @Synchronized
    override fun clearRevocation() {
        check(preferences.edit().remove(REVOCATION_KEY).commit()) {
            "Vibe Pocket could not clear the pending credential revocation."
        }
    }

    override fun saveVoiceStop(stop: VoiceStop) {
        val payload = JSONObject(stop.config.encode())
            .put("idempotencyKey", stop.idempotencyKey)
            .toString()
        check(preferences.edit().putString(VOICE_STOP_KEY, encrypt(payload)).commit()) {
            "Vibe Pocket could not save the pending voice stop."
        }
    }

    override fun loadVoiceStop(): VoiceStop? {
        val encrypted = preferences.getString(VOICE_STOP_KEY, null) ?: return null
        return runCatching { decodeVoiceStop(decrypt(encrypted)) }.getOrElse {
            throw IllegalStateException("Vibe Pocket could not read the pending voice stop.", it)
        }
    }

    override fun clearVoiceStop(idempotencyKey: String): Boolean {
        val encrypted = preferences.getString(VOICE_STOP_KEY, null) ?: return true
        val current = decodeVoiceStop(decrypt(encrypted))
        if (current.idempotencyKey != idempotencyKey) return false
        check(preferences.edit().remove(VOICE_STOP_KEY).commit()) {
            "Vibe Pocket could not clear the pending voice stop."
        }
        return true
    }

    override fun commit(config: Config) {
        check(
            preferences.edit()
                .putString(CONFIG_KEY, encrypt(config.encode()))
                .remove(CLAIM_KEY)
                .commit(),
        ) {
            "Vibe Pocket could not commit the Bridge configuration."
        }
    }

    @Synchronized
    override fun forget(config: Config) {
        val queued = loadRevocations().let { existing ->
            if (existing.any { it.matches(config) }) existing else existing + config
        }
        check(
            preferences.edit()
                .putString(REVOCATION_KEY, encrypt(encodeRevocations(queued)))
                .remove(CONFIG_KEY)
                .remove(CLAIM_KEY)
                .commit(),
        ) {
            "Vibe Pocket could not forget the Bridge configuration."
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
        const val CLAIM_KEY = "encrypted_pairing_claim"
        const val REVOCATION_KEY = "encrypted_revocation"
        const val VOICE_STOP_KEY = "encrypted_voice_stop"
        const val KEY_ALIAS = "vibe-pocket-connection"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

private fun Config.encode(): String = JSONObject()
    .put("baseUrl", normalizedUrl)
    .put("token", credential)
    .toString()

private fun decodeConfig(value: String): Config {
    val payload = JSONObject(value)
    return Config(
        baseUrl = payload.getString("baseUrl"),
        credential = payload.getString("token"),
    )
}

private fun encodeRevocations(configs: List<Config>): String = JSONArray().apply {
    configs.forEach { put(JSONObject(it.encode())) }
}.toString()

private fun decodeRevocations(value: String): List<Config> {
    if (value.trimStart().startsWith("{")) return listOf(decodeConfig(value))
    val payload = JSONArray(value)
    return (0 until payload.length()).map { index ->
        decodeConfig(payload.getJSONObject(index).toString())
    }
}

private fun Config.matches(other: Config): Boolean =
    normalizedUrl == other.normalizedUrl && credential == other.credential

private fun decodeVoiceStop(value: String): VoiceStop {
    val payload = JSONObject(value)
    return VoiceStop(
        config = Config(
            baseUrl = payload.getString("baseUrl"),
            credential = payload.getString("token"),
        ),
        idempotencyKey = payload.getString("idempotencyKey"),
    )
}
