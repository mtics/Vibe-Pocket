package au.edu.uts.vibepocket.bridge

import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.Invitation
import au.edu.uts.vibepocket.control.Command
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
import java.util.UUID
import java.util.concurrent.Executors

interface Client {
    suspend fun snapshot(config: Config): Snapshot
    suspend fun command(config: Config, command: Command)
    suspend fun claim(invitation: Invitation, nonce: String): Config =
        throw Failure("This build cannot claim pairing invitations.")
    suspend fun revoke(config: Config) = Unit
    suspend fun stopVoice(config: Config, idempotencyKey: String) =
        command(config, Command.VoiceStop)
}

class Http : Client {
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
        call(
            config.normalizedUrl,
            "/v1/pocket/commands",
            "POST",
            Command.VoiceStop.encode(),
            config.credential,
            idempotencyKey = idempotencyKey,
            readTimeoutMillis = TransactionTimeoutMillis,
        )
        Unit
    }

    override suspend fun claim(invitation: Invitation, nonce: String): Config = withContext(Dispatchers.IO) {
        val body = JSONObject().put("code", invitation.code).put("nonce", nonce)
        val response = try {
            call(invitation.origin, "/v1/pairing/claim", "POST", body)
        } catch (error: IOException) {
            call(invitation.origin, "/v1/pairing/claim", "POST", body)
        }
        if (response.optInt("protocolVersion", -1) != ProtocolVersion) {
            throw Failure("The Bridge uses an incompatible pairing protocol.")
        }
        val capabilities = response.optJSONArray("capabilities")
        val hasDeviceCredential = capabilities != null && (0 until capabilities.length())
            .any { capabilities.optString(it) == "device_credentials" }
        if (!hasDeviceCredential) throw Failure("The Bridge cannot issue a device credential.")
        val claimed = Config(response.getString("baseUrl"), response.getString("token"))
        if (!claimed.isDeviceCredential) throw Failure("The Bridge returned an invalid device credential.")
        if (claimed.normalizedUrl != invitation.origin) {
            throw Failure("The Bridge returned pairing credentials for a different address.")
        }
        Config(invitation.origin, claimed.credential)
    }

    override suspend fun snapshot(config: Config): Snapshot = withContext(Dispatchers.IO) {
        decode(call(config.normalizedUrl, "/v1/pocket/snapshot", "GET", credential = config.credential))
    }

    override suspend fun command(config: Config, command: Command) = withContext(Dispatchers.IO) {
        call(config.normalizedUrl, "/v1/pocket/commands", "POST", command.encode(), config.credential)
        Unit
    }

    private fun call(
        baseUrl: String,
        path: String,
        method: String,
        body: JSONObject? = null,
        credential: String? = null,
        idempotencyKey: String? = null,
        readTimeoutMillis: Int = DefaultReadTimeoutMillis,
    ): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = readTimeoutMillis
            setRequestProperty("Accept", "application/json")
            credential?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Idempotency-Key", idempotencyKey ?: UUID.randomUUID().toString())
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
        }
        try {
            val status = connection.responseCode
            val response = readResponse(
                stream = if (status in 200..299) connection.inputStream else connection.errorStream,
                contentLength = connection.contentLengthLong,
            ).toString(Charsets.UTF_8)
            if (status !in 200..299) {
                val detail = runCatching {
                    JSONObject(response).getJSONObject("error").getString("message")
                }.getOrDefault("The Vibe Pocket bridge rejected this action.")
                throw Failure(detail, status)
            }
            return runCatching { JSONObject(response) }.getOrElse {
                throw Failure("The Vibe Pocket bridge returned an invalid response.")
            }
        } finally {
            connection.disconnect()
        }
    }
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

class Failure(message: String, val statusCode: Int? = null) : IllegalStateException(message)

private const val ProtocolVersion = 6
internal const val MaxResponseBytes = 1_048_576
internal const val MaxEventLineBytes = 8_192
private const val MaxEventFieldChars = 128
private const val MaxEventIdChars = 1_024
private const val DefaultReadTimeoutMillis = 75_000
private const val TransactionTimeoutMillis = 3_000
