package au.edu.uts.vibepocket.bridge

import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.Invitation
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
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
}

class Http : Client {
    override suspend fun revoke(config: Config) = withContext(Dispatchers.IO) {
        call(config.normalizedUrl, "/v1/pocket/devices/current", "DELETE", credential = config.credential)
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
    ): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 75_000
            setRequestProperty("Accept", "application/json")
            credential?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Idempotency-Key", UUID.randomUUID().toString())
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
        }
        val status = connection.responseCode
        val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use(BufferedReader::readText)
            .orEmpty()
        connection.disconnect()
        if (status !in 200..299) {
            val detail = runCatching {
                JSONObject(response).getJSONObject("error").getString("message")
            }.getOrDefault("The Vibe Pocket bridge rejected this action.")
            throw Failure(detail)
        }
        return runCatching { JSONObject(response) }.getOrElse {
            throw Failure("The Vibe Pocket bridge returned an invalid response.")
        }
    }
}

class Events(
    private val config: Config,
    lastEventId: String?,
    private val onConnected: () -> Unit,
    private val onSnapshotChanged: () -> Unit,
    private val onEventId: (String) -> Unit,
    private val onDisconnected: (String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var currentLastEventId = lastEventId
    private val executor = Executors.newSingleThreadExecutor()

    fun start() {
        if (running) return
        running = true
        executor.execute {
            var retryDelay = 1_000L
            while (running) {
                val result = runCatching { consume() }
                if (result.isSuccess) retryDelay = 1_000L
                if (running && result.isFailure) {
                    onDisconnected(result.exceptionOrNull()?.message ?: "Connection lost.")
                }
                if (running) {
                    runCatching { Thread.sleep(retryDelay) }
                    retryDelay = (retryDelay * 2).coerceAtMost(8_000L)
                }
            }
        }
    }

    fun stop() {
        running = false
        connection?.disconnect()
        connection = null
        executor.shutdownNow()
    }

    private fun consume() {
        val stream = (URL(config.normalizedUrl + "/v1/pocket/events").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 0
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer ${config.credential}")
            currentLastEventId?.takeIf(String::isNotBlank)?.let { setRequestProperty("Last-Event-ID", it) }
        }
        connection = stream
        if (stream.responseCode != HttpURLConnection.HTTP_OK) {
            stream.disconnect()
            throw Failure("The controller event connection was rejected.")
        }
        onConnected()
        var event = ""
        var eventId = ""
        stream.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (!running) return@forEach
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("id:") -> eventId = line.removePrefix("id:").trim()
                    line.isBlank() -> {
                        eventId.takeIf(String::isNotBlank)?.let {
                            currentLastEventId = it
                            onEventId(it)
                        }
                        if (event == "snapshot_changed") onSnapshotChanged()
                        event = ""
                        eventId = ""
                    }
                }
            }
        }
        stream.disconnect()
        connection = null
    }
}

class Failure(message: String) : IllegalStateException(message)

private const val ProtocolVersion = 6
