package au.edu.uts.vibepocket.bridge

import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.Invitation
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.encode
import au.edu.uts.vibepocket.control.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

interface Client {
    suspend fun snapshot(config: Config): Snapshot
    suspend fun command(config: Config, command: Command)
    suspend fun command(config: Config, command: Command, operationId: String) =
        command(config, command)
    suspend fun commandResult(config: Config, operationId: String): CommandResult =
        throw IOException("Command result lookup is unavailable.")
    suspend fun claim(invitation: Invitation, nonce: String): Config =
        throw Failure("This build cannot claim pairing invitations.")
    suspend fun claimPending(invitation: Invitation, nonce: String): IssuedCredential =
        IssuedCredential(claim(invitation, nonce), invitation.expiresAtMillis)
    suspend fun activate(config: Config): Unit =
        throw Failure("This build cannot activate pairing credentials.")
    suspend fun revoke(config: Config) = Unit
    suspend fun stopVoice(config: Config, idempotencyKey: String) =
        command(config, Command.VoiceStop)
}

data class IssuedCredential(
    val config: Config,
    val expiresAtMillis: Long,
)

enum class CommandStatus {
    ACCEPTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
}

data class RemoteError(
    val code: String?,
    val message: String,
    val statusCode: Int? = null,
)

sealed interface CommandResult {
    data class Found(
        val status: CommandStatus,
        val error: RemoteError? = null,
    ) : CommandResult

    data object NotFound : CommandResult
}

class Http(
    private val openConnection: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) : Client {
    override suspend fun revoke(config: Config) = withContext(Dispatchers.IO) {
        call(
            config.normalizedUrl,
            "/v1/pocket/devices/current",
            "DELETE",
            credential = config.credential,
            readTimeoutMillis = TransactionTimeoutMillis,
        )
        Unit
    }

    override suspend fun stopVoice(config: Config, idempotencyKey: String) = withContext(Dispatchers.IO) {
        val result = call(
            config.normalizedUrl,
            "/v1/pocket/commands",
            "POST",
            Command.VoiceStop.encode(),
            config.credential,
            idempotencyKey = idempotencyKey,
            readTimeoutMillis = TransactionTimeoutMillis,
            expectedStatus = HttpURLConnection.HTTP_OK,
        )
        requireSucceeded(decodeCommandResult(result))
    }

    override suspend fun claim(invitation: Invitation, nonce: String): Config =
        claimPending(invitation, nonce).config

    override suspend fun claimPending(invitation: Invitation, nonce: String): IssuedCredential =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("code", invitation.code).put("nonce", nonce)
            val response = try {
                call(invitation.origin, "/v1/pairing/claim", "POST", body)
            } catch (error: IOException) {
                call(invitation.origin, "/v1/pairing/claim", "POST", body)
            }
            decodePairingClaim(invitation, response)
        }

    override suspend fun activate(config: Config) = withContext(Dispatchers.IO) {
        call(
            config.normalizedUrl,
            "/v1/pairing/commit",
            "POST",
            credential = config.credential,
            readTimeoutMillis = TransactionTimeoutMillis,
        )
        Unit
    }

    override suspend fun snapshot(config: Config): Snapshot = withContext(Dispatchers.IO) {
        decode(call(config.normalizedUrl, "/v1/pocket/snapshot", "GET", credential = config.credential))
    }

    override suspend fun command(config: Config, command: Command) =
        command(config, command, UUID.randomUUID().toString())

    override suspend fun command(
        config: Config,
        command: Command,
        operationId: String,
    ) = withContext(Dispatchers.IO) {
        requireUuid(operationId)
        val result = call(
            config.normalizedUrl,
            "/v1/pocket/commands",
            "POST",
            command.encode(),
            config.credential,
            idempotencyKey = operationId,
            expectedStatus = HttpURLConnection.HTTP_OK,
        )
        requireSucceeded(decodeCommandResult(result))
    }

    override suspend fun commandResult(config: Config, operationId: String): CommandResult =
        withContext(Dispatchers.IO) {
            requireUuid(operationId)
            val response = try {
                call(
                    config.normalizedUrl,
                    "/v1/pocket/commands/$operationId",
                    "GET",
                    credential = config.credential,
                    expectedStatus = HttpURLConnection.HTTP_OK,
                )
            } catch (error: Failure) {
                if (error.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    return@withContext CommandResult.NotFound
                }
                throw error
            }
            decodeCommandResult(response)
        }

    private fun call(
        baseUrl: String,
        path: String,
        method: String,
        body: JSONObject? = null,
        credential: String? = null,
        idempotencyKey: String? = null,
        readTimeoutMillis: Int = DefaultReadTimeoutMillis,
        expectedStatus: Int? = null,
    ): JSONObject {
        val connection = openConnection(URL(baseUrl + path)).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = readTimeoutMillis
            setRequestProperty("Accept", "application/json")
            credential?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                idempotencyKey?.let { setRequestProperty("Idempotency-Key", it) }
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
        }
        try {
            val status = connection.responseCode
            val response = readResponse(
                stream = if (status in 200..299) connection.inputStream else connection.errorStream,
                contentLength = connection.contentLengthLong,
            ).toString(Charsets.UTF_8)
            if (status !in 200..299 || expectedStatus != null && status != expectedStatus) {
                throw decodeFailure(response, status)
            }
            if (response.isBlank()) return JSONObject()
            return runCatching { JSONObject(response) }.getOrElse {
                throw Failure("The Vibe Pocket bridge returned an invalid response.")
            }
        } finally {
            connection.disconnect()
        }
    }
}

internal fun decodePairingClaim(invitation: Invitation, response: JSONObject): IssuedCredential {
    val bridgeProtocol = response.optInt("protocolVersion", -1)
    if (bridgeProtocol != ProtocolVersion) {
        val problem = if (bridgeProtocol > 0) {
            "The Bridge uses pairing protocol $bridgeProtocol, but this app requires $ProtocolVersion."
        } else {
            "The Bridge did not report a valid pairing protocol; this app requires $ProtocolVersion."
        }
        throw Failure("$problem Update Vibe Pocket Bridge Host and try again.")
    }
    val capabilities = response.optJSONArray("capabilities")
    val values = if (capabilities == null) emptySet() else (0 until capabilities.length())
        .mapTo(mutableSetOf()) { capabilities.optString(it) }
    if ("device_credentials" !in values) throw Failure("The Bridge cannot issue a device credential.")
    if ("pairing_commit" !in values) throw Failure("The Bridge cannot activate a pairing credential.")
    if ("command_results" !in values) throw Failure("The Bridge cannot recover command results.")
    if ("binding_context" !in values) throw Failure("The Bridge cannot reject stale controller bindings.")
    if (response.optString("credentialState") != "pending") {
        throw Failure("The Bridge returned a pairing credential in an invalid state.")
    }
    val expiresAtMillis = runCatching {
        Instant.parse(response.getString("credentialExpiresAt")).toEpochMilli()
    }.getOrElse { throw Failure("The Bridge returned an invalid pairing credential expiry.") }
    val claimed = runCatching { Config(response.getString("baseUrl"), response.getString("token")) }
        .getOrElse { throw Failure("The Bridge returned an invalid device credential.") }
    if (!claimed.isDeviceCredential) throw Failure("The Bridge returned an invalid device credential.")
    if (claimed.normalizedUrl != invitation.origin) {
        throw Failure("The Bridge returned pairing credentials for a different address.")
    }
    return IssuedCredential(Config(invitation.origin, claimed.credential), expiresAtMillis)
}

internal fun decodeCommandResult(response: JSONObject): CommandResult.Found {
    val status = when (response.optString("status")) {
        "accepted" -> CommandStatus.ACCEPTED
        "running" -> CommandStatus.RUNNING
        "succeeded" -> CommandStatus.SUCCEEDED
        "failed" -> CommandStatus.FAILED
        "unknown" -> CommandStatus.UNKNOWN
        else -> throw Failure("The Vibe Pocket bridge returned an invalid command result.")
    }
    val error = response.optJSONObject("error")?.let(::decodeRemoteError)
    return CommandResult.Found(status, error)
}

internal fun decodeFailure(response: String, statusCode: Int): Failure {
    val error = runCatching { JSONObject(response).optJSONObject("error") }
        .getOrNull()
        ?.let(::decodeRemoteError)
    return Failure(
        message = error?.message ?: "The Vibe Pocket bridge rejected this action.",
        statusCode = statusCode,
        errorCode = error?.code,
    )
}

private fun decodeRemoteError(error: JSONObject): RemoteError = RemoteError(
    code = error.optString("code").takeIf(String::isNotBlank),
    message = error.optString("message")
        .takeIf(String::isNotBlank)
        ?: "The Vibe Pocket bridge rejected this action.",
    statusCode = error.optInt("status").takeIf { it in 400..599 },
)

private fun requireSucceeded(result: CommandResult.Found) {
    when (result.status) {
        CommandStatus.SUCCEEDED -> Unit
        CommandStatus.ACCEPTED,
        CommandStatus.RUNNING,
        -> throw IOException("The command result is not terminal yet.")
        CommandStatus.FAILED,
        CommandStatus.UNKNOWN,
        -> throw Failure(
            message = result.error?.message ?: "The Vibe Pocket bridge could not confirm this command.",
            statusCode = result.error?.statusCode ?: HttpURLConnection.HTTP_CONFLICT,
            errorCode = result.error?.code,
        )
    }
}

private fun requireUuid(value: String) {
    require(runCatching { UUID.fromString(value) }.isSuccess) { "The command operation ID is invalid." }
}

internal fun readResponse(
    stream: InputStream?,
    contentLength: Long,
    limit: Int = MaxResponseBytes,
): ByteArray {
    if (contentLength > limit) throw Failure("The Vibe Pocket bridge response is too large.")
    if (stream == null) return ByteArray(0)
    val output = ByteArrayOutputStream(contentLength.coerceIn(0, limit.toLong()).toInt())
    val buffer = ByteArray(8_192)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        if (output.size() > limit - count) {
            throw Failure("The Vibe Pocket bridge response is too large.")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal interface EventStream {
    fun start()
    fun stop()
}

internal data class EventCallbacks(
    val connected: () -> Unit,
    val snapshotChanged: () -> Unit,
    val eventId: (String) -> Unit,
    val disconnected: (String) -> Unit,
    val unauthorized: () -> Unit,
)

internal fun interface EventFactory {
    fun create(config: Config, lastEventId: String?, callbacks: EventCallbacks): EventStream
}

internal val NetworkEvents = EventFactory { config, lastEventId, callbacks ->
    Events(
        config = config,
        lastEventId = lastEventId,
        onConnected = callbacks.connected,
        onSnapshotChanged = callbacks.snapshotChanged,
        onEventId = callbacks.eventId,
        onDisconnected = callbacks.disconnected,
        onUnauthorized = callbacks.unauthorized,
    )
}

class Events(
    private val config: Config,
    lastEventId: String?,
    private val onConnected: () -> Unit,
    private val onSnapshotChanged: () -> Unit,
    private val onEventId: (String) -> Unit,
    private val onDisconnected: (String) -> Unit,
    private val onUnauthorized: () -> Unit,
) : EventStream {
    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var currentLastEventId = lastEventId
    private val executor = Executors.newSingleThreadExecutor()

    override fun start() {
        if (running) return
        running = true
        executor.execute {
            val backoff = EventBackoff()
            while (running) {
                val result = runCatching { consume(backoff::healthy) }
                val failure = result.exceptionOrNull()
                if (running && (failure as? Failure)?.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    running = false
                    onUnauthorized()
                    break
                }
                if (running && failure != null) {
                    onDisconnected(failure.message ?: "Connection lost.")
                }
                if (running) {
                    runCatching { Thread.sleep(backoff.nextDelay()) }
                }
            }
            executor.shutdown()
        }
    }

    override fun stop() {
        running = false
        connection?.disconnect()
        connection = null
        executor.shutdownNow()
    }

    private fun consume(onHealthy: () -> Unit) {
        val stream = (URL(config.normalizedUrl + "/v1/pocket/events").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 0
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer ${config.credential}")
            currentLastEventId?.takeIf(String::isNotBlank)?.let { setRequestProperty("Last-Event-ID", it) }
        }
        connection = stream
        try {
            val status = stream.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                readResponse(stream.errorStream, stream.contentLengthLong)
                throw Failure("The controller event connection was rejected.", status)
            }
            onHealthy()
            if (stream.contentLengthLong > MaxResponseBytes) {
                throw Failure("The controller event response is too large.")
            }
            onConnected()
            consumeEvents(
                input = BufferedInputStream(stream.inputStream),
                active = { running },
                onEventId = {
                    currentLastEventId = it
                    onEventId(it)
                },
                onSnapshotChanged = onSnapshotChanged,
            )
            if (running) throw Failure("The controller event connection ended.")
        } finally {
            stream.disconnect()
            connection = null
        }
    }
}

internal class EventBackoff(
    private val initialDelayMillis: Long = 1_000,
    private val maxDelayMillis: Long = 8_000,
) {
    private var delayMillis = initialDelayMillis

    fun healthy() {
        delayMillis = initialDelayMillis
    }

    fun nextDelay(): Long = delayMillis.also {
        delayMillis = (delayMillis * 2).coerceAtMost(maxDelayMillis)
    }
}

internal fun consumeEvents(
    input: InputStream,
    active: () -> Boolean = { true },
    onEventId: (String) -> Unit,
    onSnapshotChanged: () -> Unit,
) {
    var event = ""
    var eventId = ""
    while (active()) {
        val line = readEventLine(input) ?: return
        when {
            line.startsWith("event:") -> {
                event = line.removePrefix("event:").trim().also {
                    if (it.length > MaxEventFieldChars) throw Failure("The controller event type is too large.")
                }
            }
            line.startsWith("id:") -> {
                eventId = line.removePrefix("id:").trim().also {
                    if (it.length > MaxEventIdChars) throw Failure("The controller event ID is too large.")
                }
            }
            line.isBlank() -> {
                eventId.takeIf(String::isNotBlank)?.let(onEventId)
                if (event == "snapshot_changed") onSnapshotChanged()
                event = ""
                eventId = ""
            }
        }
    }
}

private fun readEventLine(input: InputStream): String? {
    val output = ByteArrayOutputStream()
    while (true) {
        val byte = input.read()
        if (byte < 0) return if (output.size() == 0) null else output.toByteArray().toString(Charsets.UTF_8)
        if (byte == '\n'.code) return output.toByteArray().toString(Charsets.UTF_8).removeSuffix("\r")
        if (output.size() >= MaxEventLineBytes) {
            throw Failure("The controller event line is too large.")
        }
        output.write(byte)
    }
}

class Failure(
    message: String,
    val statusCode: Int? = null,
    val errorCode: String? = null,
) : IllegalStateException(message)

internal const val ProtocolVersion = 11
internal const val MaxResponseBytes = 1_048_576
internal const val MaxEventLineBytes = 8_192
private const val MaxEventFieldChars = 128
private const val MaxEventIdChars = 1_024
private const val DefaultReadTimeoutMillis = 75_000
private const val TransactionTimeoutMillis = 3_000
