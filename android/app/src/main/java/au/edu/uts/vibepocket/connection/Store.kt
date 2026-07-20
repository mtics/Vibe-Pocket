package au.edu.uts.vibepocket.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.ContextTransition
import au.edu.uts.vibepocket.control.decodeCommand
import au.edu.uts.vibepocket.control.encode
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
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
    fun retireClaim(claim: Claim): Boolean {
        if (loadClaim() != claim) return false
        claim.issued?.let(::enqueueRevocation)
        if (loadClaim() != claim) return false
        clearClaim()
        return true
    }

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
    fun savePendingCommand(command: PendingCommand)
    fun trySavePendingCommand(command: PendingCommand): Boolean {
        return when (val loaded = loadPendingCommandsRecord()) {
            LoadOutcome.Absent -> {
                savePendingCommand(command)
                true
            }
            is LoadOutcome.Loaded -> when {
                loaded.value.size >= PendingCommandLimit -> false
                loaded.value.any { it.uiId == command.uiId } -> false
                else -> {
                    savePendingCommand(command)
                    true
                }
            }
            is LoadOutcome.RecoverableError -> throw loaded.asException()
        }
    }
    fun loadPendingCommand(): PendingCommand?
    fun loadPendingCommands(): List<PendingCommand> = listOfNotNull(loadPendingCommand())
    fun markPendingCommandDispatched(operationId: String): PendingCommand
    fun clearPendingCommand(operationId: String): Boolean
    fun clearPendingCommands() {
        loadPendingCommands().forEach { command ->
            check(clearPendingCommand(command.operationId)) {
                "Vibe Pocket found a different pending command while retiring the outbox."
            }
        }
    }

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
    fun loadPendingCommandsRecord(): LoadOutcome<List<PendingCommand>> =
        loadOutcome(LifecycleRecord.COMMAND_OUTBOX, ::loadPendingCommands)
            .let { loaded ->
                if (loaded is LoadOutcome.Loaded && loaded.value.isEmpty()) LoadOutcome.Absent else loaded
            }
    fun loadPendingCommandRecord(): LoadOutcome<PendingCommand> = loadPendingCommandsRecord().head()

    fun commit(config: Config) {
        save(config)
        clearClaim()
    }

    fun forget(config: Config) {
        enqueueRevocation(config)
        clearPendingCommands()
        clear()
    }

    fun invalidate() {
        clearPendingCommands()
        clear()
    }
}

enum class LifecycleRecord(val description: String) {
    CONFIG("saved Bridge configuration"),
    CLAIM("pending pairing claim"),
    REVOCATIONS("pending credential revocations"),
    VOICE_STOP("pending voice stop"),
    COMMAND_OUTBOX("pending command result"),
}

sealed interface LoadOutcome<out T> {
    data object Absent : LoadOutcome<Nothing>
    data class Loaded<T>(val value: T) : LoadOutcome<T>
    data class RecoverableError(
        val record: LifecycleRecord,
        val cause: Throwable,
        val settled: Boolean = false,
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
    val pendingCommands: LoadOutcome<List<PendingCommand>>,
) {
    val pendingCommand: LoadOutcome<PendingCommand>
        get() = pendingCommands.head()

    val errors: List<LoadOutcome.RecoverableError>
        get() = listOf(config, claim, revocations, voiceStop, pendingCommands)
            .filterIsInstance<LoadOutcome.RecoverableError>()
            .filterNot(LoadOutcome.RecoverableError::settled)
}

fun Store.loadLifecycle(): LifecycleLoads = LifecycleLoads(
    config = loadConfigRecord(),
    claim = loadClaimRecord(),
    revocations = loadRevocationsRecord(),
    voiceStop = loadVoiceStopRecord(),
    pendingCommands = loadPendingCommandsRecord(),
)

fun <T> LoadOutcome<T>.valueOrNull(): T? = (this as? LoadOutcome.Loaded<T>)?.value

private fun <T> LoadOutcome<List<T>>.head(): LoadOutcome<T> = when (this) {
    LoadOutcome.Absent -> LoadOutcome.Absent
    is LoadOutcome.Loaded -> value.firstOrNull()?.let { LoadOutcome.Loaded(it) } ?: LoadOutcome.Absent
    is LoadOutcome.RecoverableError -> this
}

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
    val issued: Config? = null,
    val credentialExpiresAtMillis: Long? = null,
) {
    init {
        require((issued == null) == (credentialExpiresAtMillis == null)) {
            "A pending pairing credential and its expiry must be stored together."
        }
    }
}

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

data class PendingCommand(
    val config: Config,
    val command: Command,
    val operationId: String,
    val uiId: String,
    val transition: ContextTransition? = null,
    val phase: Phase = Phase.PREPARED,
) {
    enum class Phase {
        PREPARED,
        DISPATCH_ATTEMPTED,
    }

    init {
        require(runCatching { UUID.fromString(operationId) }.isSuccess) {
            "The pending command operation ID is invalid."
        }
        require(uiId.length in 1..512 && uiId.none(Char::isISOControl)) {
            "The pending command UI ID is invalid."
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
            .put("invitationExpiresAtMillis", claim.invitation.expiresAtMillis)
            .put("nonce", claim.nonce)
            .apply {
                claim.issued?.let {
                    put("issued", JSONObject(it.encode()))
                    put("credentialExpiresAtMillis", claim.credentialExpiresAtMillis)
                }
            }
            .toString()
        check(preferences.edit().putString(CLAIM_KEY, encrypt(payload)).commit()) {
            "Vibe Pocket could not save the pending pairing claim."
        }
    }

    override fun loadClaim(): Claim? = when (val loaded = loadClaimRecord()) {
        LoadOutcome.Absent -> null
        is LoadOutcome.Loaded -> loaded.value
        is LoadOutcome.RecoverableError -> throw loaded.asException()
    }

    override fun loadClaimRecord(): LoadOutcome<Claim> = loadRecord(
        key = CLAIM_KEY,
        record = LifecycleRecord.CLAIM,
    ) { value ->
        val payload = JSONObject(value)
        Claim(
            invitation = Invitation(
                payload.getString("origin"),
                payload.getString("code"),
                payload.optLong("invitationExpiresAtMillis", Long.MAX_VALUE),
            ),
            nonce = payload.getString("nonce"),
            issued = payload.optJSONObject("issued")?.let { decodeConfig(it.toString()) },
            credentialExpiresAtMillis = payload.optJSONObject("issued")?.let {
                payload.getLong("credentialExpiresAtMillis")
            },
        )
    }

    override fun clearClaim() {
        check(preferences.edit().remove(CLAIM_KEY).commit()) {
            "Vibe Pocket could not clear the pending pairing claim."
        }
    }

    @Synchronized
    override fun retireClaim(claim: Claim): Boolean {
        if (loadClaim() != claim) return false
        val queued = claim.issued?.let { issued ->
            loadRevocations().let { existing ->
                if (existing.any { it.matches(issued) }) existing else existing + issued
            }
        }
        val edit = preferences.edit().remove(CLAIM_KEY)
        if (queued != null) edit.putString(REVOCATION_KEY, encrypt(encodeRevocations(queued)))
        check(edit.commit()) { "Vibe Pocket could not retire the pending pairing claim." }
        return true
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
        return when (val loaded = loadRevocationsRecord()) {
            LoadOutcome.Absent -> emptyList()
            is LoadOutcome.Loaded -> loaded.value
            is LoadOutcome.RecoverableError -> throw loaded.asException()
        }
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

    override fun savePendingCommand(command: PendingCommand) {
        check(trySavePendingCommand(command)) {
            "Vibe Pocket could not append the pending command."
        }
    }

    @Synchronized
    override fun trySavePendingCommand(command: PendingCommand): Boolean {
        val queued = when (val loaded = loadPendingCommandsRecord()) {
            LoadOutcome.Absent -> emptyList()
            is LoadOutcome.Loaded -> loaded.value
            is LoadOutcome.RecoverableError -> throw loaded.asException()
        }
        if (queued.size >= PendingCommandLimit || queued.any { it.uiId == command.uiId }) return false
        val payload = encodePendingCommands(queued + command)
        check(preferences.edit().putString(COMMAND_OUTBOX_KEY, encrypt(payload)).commit()) {
            "Vibe Pocket could not save the pending command."
        }
        return true
    }

    override fun loadPendingCommand(): PendingCommand? = loadPendingCommands().firstOrNull()

    @Synchronized
    override fun loadPendingCommands(): List<PendingCommand> =
        loadPendingCommandsRecord().valueOrNull().orEmpty()

    @Synchronized
    override fun loadPendingCommandsRecord(): LoadOutcome<List<PendingCommand>> {
        val loaded = loadRecord(
            key = COMMAND_OUTBOX_KEY,
            record = LifecycleRecord.COMMAND_OUTBOX,
            decode = ::decodePendingCommands,
        )
        val legacy = (loaded as? LoadOutcome.RecoverableError)?.cause as? LegacyPendingCommand
            ?: return loaded
        if (!preferences.edit().remove(COMMAND_OUTBOX_KEY).commit()) {
            return LoadOutcome.RecoverableError(
                LifecycleRecord.COMMAND_OUTBOX,
                IllegalStateException("Vibe Pocket could not retire its legacy pending command."),
            )
        }
        return LoadOutcome.RecoverableError(
            LifecycleRecord.COMMAND_OUTBOX,
            legacy,
            settled = true,
        )
    }

    @Synchronized
    override fun markPendingCommandDispatched(operationId: String): PendingCommand {
        val encrypted = preferences.getString(COMMAND_OUTBOX_KEY, null)
            ?: throw IllegalStateException("Vibe Pocket could not find the pending command to dispatch.")
        val queued = decodePendingCommands(decrypt(encrypted))
        val current = queued.firstOrNull()
            ?.takeIf { it.operationId == operationId }
            ?: throw IllegalStateException("Vibe Pocket found a different pending command before dispatch.")
        if (current.phase == PendingCommand.Phase.DISPATCH_ATTEMPTED) return current

        val dispatched = current.copy(phase = PendingCommand.Phase.DISPATCH_ATTEMPTED)
        val updated = listOf(dispatched) + queued.drop(1)
        check(
            preferences.edit()
                .putString(COMMAND_OUTBOX_KEY, encrypt(encodePendingCommands(updated)))
                .commit(),
        ) {
            "Vibe Pocket could not record the pending command dispatch attempt."
        }
        return dispatched
    }

    @Synchronized
    override fun clearPendingCommand(operationId: String): Boolean {
        val encrypted = preferences.getString(COMMAND_OUTBOX_KEY, null) ?: return true
        val queued = decodePendingCommands(decrypt(encrypted))
        if (queued.firstOrNull()?.operationId != operationId) return false
        val remaining = queued.drop(1)
        val edit = preferences.edit()
        if (remaining.isEmpty()) {
            edit.remove(COMMAND_OUTBOX_KEY)
        } else {
            edit.putString(COMMAND_OUTBOX_KEY, encrypt(encodePendingCommands(remaining)))
        }
        check(edit.commit()) {
            "Vibe Pocket could not clear the pending command."
        }
        return true
    }

    @Synchronized
    override fun clearPendingCommands() {
        check(preferences.edit().remove(COMMAND_OUTBOX_KEY).commit()) {
            "Vibe Pocket could not retire the pending command outbox."
        }
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
                .remove(COMMAND_OUTBOX_KEY)
                .commit(),
        ) {
            "Vibe Pocket could not forget the Bridge configuration."
        }
    }

    override fun invalidate() {
        check(
            preferences.edit()
                .remove(CONFIG_KEY)
                .remove(COMMAND_OUTBOX_KEY)
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
        const val COMMAND_OUTBOX_KEY = "encrypted_command_outbox"
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

internal fun encodePendingCommand(command: PendingCommand): String = JSONObject(command.config.encode())
    .put("version", PendingCommandVersion)
    .put("command", command.command.encode())
    .put("operationId", command.operationId)
    .put("uiId", command.uiId)
    .put("phase", command.phase.wireValue)
    .apply { command.transition?.let { put("transition", encodeTransition(it)) } }
    .toString()

internal fun decodePendingCommand(value: String): PendingCommand {
    val payload = JSONObject(value)
    val version = payload.optInt("version", 1)
    if (version == 1) throw LegacyPendingCommand()
    require(version in 2..PendingCommandVersion) { "The pending command version is unsupported." }
    val encodedCommand = payload.optJSONObject("command")
        ?: throw IllegalArgumentException("The pending command body is invalid.")
    return PendingCommand(
        config = Config(
            baseUrl = payload.getString("baseUrl"),
            credential = payload.getString("token"),
        ),
        command = decodeCommand(encodedCommand),
        operationId = payload.getString("operationId"),
        uiId = payload.getString("uiId"),
        transition = when {
            !payload.has("transition") -> null
            else -> payload.optJSONObject("transition")
                ?.let(::decodeTransition)
                ?: throw IllegalArgumentException("The pending command transition target is invalid.")
        },
        phase = if (version == 2) {
            PendingCommand.Phase.DISPATCH_ATTEMPTED
        } else {
            decodePendingCommandPhase(payload.getString("phase"))
        },
    )
}

private val PendingCommand.Phase.wireValue: String
    get() = when (this) {
        PendingCommand.Phase.PREPARED -> "prepared"
        PendingCommand.Phase.DISPATCH_ATTEMPTED -> "dispatch_attempted"
    }

private fun decodePendingCommandPhase(value: String): PendingCommand.Phase = when (value) {
    "prepared" -> PendingCommand.Phase.PREPARED
    "dispatch_attempted" -> PendingCommand.Phase.DISPATCH_ATTEMPTED
    else -> throw IllegalArgumentException("The pending command dispatch phase is invalid.")
}

internal fun encodePendingCommands(commands: List<PendingCommand>): String = JSONObject()
    .put("version", PendingCommandQueueVersion)
    .put("commands", JSONArray().apply { commands.forEach { put(JSONObject(encodePendingCommand(it))) } })
    .toString()

internal fun decodePendingCommands(value: String): List<PendingCommand> {
    val payload = JSONObject(value)
    if (!payload.has("commands")) return listOf(decodePendingCommand(value))
    require(payload.optInt("version") == PendingCommandQueueVersion) {
        "The pending command queue version is unsupported."
    }
    val commands = payload.optJSONArray("commands")
        ?: throw IllegalArgumentException("The pending command queue is invalid.")
    require(commands.length() in 1..PendingCommandLimit) {
        "The pending command queue size is invalid."
    }
    return (0 until commands.length()).map { index ->
        decodePendingCommand(commands.getJSONObject(index).toString())
    }.also { decoded ->
        require(decoded.map(PendingCommand::operationId).distinct().size == decoded.size) {
            "The pending command queue contains a duplicate operation ID."
        }
        require(decoded.map(PendingCommand::uiId).distinct().size == decoded.size) {
            "The pending command queue contains a duplicate UI ID."
        }
    }
}

private fun encodeTransition(transition: ContextTransition): JSONObject = when (transition) {
    is ContextTransition.NewDesktop -> JSONObject()
        .put("kind", "new_desktop")
        .apply {
            transition.baselineFocusedAgentId?.let { put("baselineFocusedAgentId", it) }
        }
    ContextTransition.Attached -> JSONObject().put("kind", "attached")
    is ContextTransition.Agent -> JSONObject().put("kind", "agent").put("id", transition.id)
    is ContextTransition.Model -> JSONObject().put("kind", "model").put("id", transition.id)
    is ContextTransition.Mode -> JSONObject().put("kind", "mode").put("id", transition.id)
    is ContextTransition.Reasoning -> JSONObject()
        .put("kind", "reasoning")
        .put("level", transition.level.wireValue)
    is ContextTransition.Layer -> JSONObject().put("kind", "layer").put("id", transition.id)
}

private fun decodeTransition(value: JSONObject): ContextTransition = when (value.getString("kind")) {
    "new_desktop" -> ContextTransition.NewDesktop(
        value.optString("baselineFocusedAgentId").takeIf(String::isNotBlank),
    )
    "attached" -> ContextTransition.Attached
    "agent" -> ContextTransition.Agent(value.getString("id"))
    "model" -> ContextTransition.Model(value.getString("id"))
    "mode" -> ContextTransition.Mode(value.getString("id"))
    "reasoning" -> ContextTransition.Reasoning(
        requireNotNull(au.edu.uts.vibepocket.control.Reasoning.Level.fromWire(value.getString("level"))) {
            "The pending command reasoning transition is invalid."
        },
    )
    "layer" -> ContextTransition.Layer(value.getString("id"))
    else -> throw IllegalArgumentException("The pending command transition target is invalid.")
}

private const val PendingCommandVersion = 3
private const val PendingCommandQueueVersion = 3
internal const val PendingCommandLimit = 32

private class LegacyPendingCommand : IllegalArgumentException(
    "The legacy pending command has no replayable command body.",
)
