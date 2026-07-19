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

    fun loadConfigRecord(): LoadOutcome<Config> = loadOutcome(LifecycleRecord.CONFIG, ::load)
    fun loadClaimRecord(): LoadOutcome<Claim> = loadOutcome(LifecycleRecord.CLAIM, ::loadClaim)
    fun loadRevocationsRecord(): LoadOutcome<List<Config>> =
        runCatching { loadRevocations() }
            .fold(
                onSuccess = { LoadOutcome.Loaded(it) },
                onFailure = { LoadOutcome.RecoverableError(LifecycleRecord.REVOCATIONS, it) },
            )
    fun loadVoiceStopRecord(): LoadOutcome<VoiceStop> =
        loadOutcome(LifecycleRecord.VOICE_STOP, ::loadVoiceStop)

    fun commit(config: Config) {
        save(config)
        clearClaim()
    }

    fun forget(config: Config) {
        enqueueRevocation(config)
        clear()
        clearClaim()
    }

    fun invalidate() {
        clear()
        clearClaim()
    }
}

enum class LifecycleRecord(val description: String) {
    CONFIG("saved Bridge configuration"),
    CLAIM("pending pairing claim"),
    REVOCATIONS("pending credential revocations"),
    VOICE_STOP("pending voice stop"),
}

sealed interface LoadOutcome<out T> {
    data object Absent : LoadOutcome<Nothing>
    data class Loaded<T>(val value: T) : LoadOutcome<T>
    data class RecoverableError(
        val record: LifecycleRecord,
        val cause: Throwable,
    ) : LoadOutcome<Nothing> {
        val message: String
            get() = "Vibe Pocket could not read the ${record.description}."

        fun asException(): IllegalStateException = IllegalStateException(message, cause)
    }
}

data class LifecycleLoads(
    val config: LoadOutcome<Config>,
    val claim: LoadOutcome<Claim>,
    val revocations: LoadOutcome<List<Config>>,
    val voiceStop: LoadOutcome<VoiceStop>,
) {
    val errors: List<LoadOutcome.RecoverableError>
        get() = listOf(config, claim, revocations, voiceStop)
            .filterIsInstance<LoadOutcome.RecoverableError>()
}

fun Store.loadLifecycle(): LifecycleLoads = LifecycleLoads(
    config = loadConfigRecord(),
    claim = loadClaimRecord(),
    revocations = loadRevocationsRecord(),
    voiceStop = loadVoiceStopRecord(),
)

fun <T> LoadOutcome<T>.valueOrNull(): T? = (this as? LoadOutcome.Loaded<T>)?.value

private fun <T : Any> loadOutcome(
    record: LifecycleRecord,
    load: () -> T?,
): LoadOutcome<T> = runCatching(load)
    .fold(
        onSuccess = { if (it == null) LoadOutcome.Absent else LoadOutcome.Loaded(it) },
        onFailure = { LoadOutcome.RecoverableError(record, it) },
    )

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

    override fun load(): Config? = loadConfigRecord().valueOrNull()

    override fun loadConfigRecord(): LoadOutcome<Config> = loadRecord(
        key = CONFIG_KEY,
        record = LifecycleRecord.CONFIG,
        decode = ::decodeConfig,
    )

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

    override fun loadClaim(): Claim? = loadClaimRecord().valueOrNull()

    override fun loadClaimRecord(): LoadOutcome<Claim> = loadRecord(
        key = CLAIM_KEY,
        record = LifecycleRecord.CLAIM,
    ) { value ->
        val payload = JSONObject(value)
        Claim(
            invitation = Invitation(payload.getString("origin"), payload.getString("code")),
            nonce = payload.getString("nonce"),
        )
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
        return loadRevocationsRecord().valueOrNull().orEmpty()
    }

    @Synchronized
    override fun loadRevocationsRecord(): LoadOutcome<List<Config>> = loadRecord(
        key = REVOCATION_KEY,
        record = LifecycleRecord.REVOCATIONS,
        decode = ::decodeRevocations,
    )

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

    override fun loadVoiceStop(): VoiceStop? = loadVoiceStopRecord().valueOrNull()

    override fun loadVoiceStopRecord(): LoadOutcome<VoiceStop> = loadRecord(
        key = VOICE_STOP_KEY,
        record = LifecycleRecord.VOICE_STOP,
        decode = ::decodeVoiceStop,
    )

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

    override fun invalidate() {
        check(
            preferences.edit()
                .remove(CONFIG_KEY)
                .remove(CLAIM_KEY)
                .commit(),
        ) {
            "Vibe Pocket could not invalidate the Bridge configuration."
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

    private fun <T> loadRecord(
        key: String,
        record: LifecycleRecord,
        decode: (String) -> T,
    ): LoadOutcome<T> {
        val encrypted = preferences.getString(key, null) ?: return LoadOutcome.Absent
        return runCatching { decode(decrypt(encrypted)) }
            .fold(
                onSuccess = { LoadOutcome.Loaded(it) },
                onFailure = { LoadOutcome.RecoverableError(record, it) },
            )
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
