package au.edu.uts.vibepocket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class PocketBridgeClient {
    suspend fun snapshot(config: ConnectionConfig): PocketSnapshot = withContext(Dispatchers.IO) {
        parseSnapshot(call(config, "/v1/pocket/snapshot", "GET"))
    }

    suspend fun command(config: ConnectionConfig, command: PocketCommand) = withContext(Dispatchers.IO) {
        call(config, "/v1/pocket/commands", "POST", command.toJson())
    }

    private fun call(
        config: ConnectionConfig,
        path: String,
        method: String,
        body: JSONObject? = null,
    ): JSONObject {
        val connection = (URL(config.normalizedUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 75_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.token}")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Idempotency-Key", java.util.UUID.randomUUID().toString())
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
            throw BridgeException(detail)
        }
        return JSONObject(response)
    }

    private fun parseSnapshot(root: JSONObject): PocketSnapshot {
        val status = root.optJSONObject("status") ?: JSONObject()
        val statusMessage = status.optString("message", "").trim()
        val controls = root.optJSONObject("controls") ?: JSONObject()
        return PocketSnapshot(
            revision = root.optString("revision", "r_0"),
            status = BridgeStatus(
                state = status.optString("state", "degraded"),
                message = statusMessage.takeUnless { it.isEmpty() || it == "null" },
            ),
            controls = DesktopControls(
                voice = controls.optBoolean("voice"),
                stop = controls.optBoolean("stop"),
                newTask = controls.optBoolean("new-task"),
                approve = controls.optBoolean("approve"),
                reject = controls.optBoolean("reject"),
            ),
        )
    }
}

class PocketEventStream(
    private val config: ConnectionConfig,
    private val onSnapshotChanged: () -> Unit,
    private val onDisconnected: (String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null
    private val executor = Executors.newSingleThreadExecutor()

    fun start() {
        if (running) return
        running = true
        executor.execute {
            while (running) {
                runCatching { consume() }.onFailure { onDisconnected(it.message ?: "Connection lost.") }
                if (running) Thread.sleep(1_500)
            }
        }
    }

    fun stop() {
        running = false
        connection?.disconnect()
        executor.shutdownNow()
    }

    private fun consume() {
        connection = (URL(config.normalizedUrl + "/v1/pocket/events").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            readTimeout = 0
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer ${config.token}")
        }
        if (connection?.responseCode != HttpURLConnection.HTTP_OK) {
            throw BridgeException("The controller event connection was rejected.")
        }
        var event = ""
        connection?.inputStream?.bufferedReader()?.useLines { lines ->
            lines.forEach { line ->
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.isBlank() && event == "snapshot_changed" -> {
                        onSnapshotChanged()
                        event = ""
                    }
                }
            }
        }
    }
}

private fun PocketCommand.toJson(): JSONObject = when (this) {
    PocketCommand.Attach -> JSONObject().put("kind", "attach")
    PocketCommand.Voice -> JSONObject().put("kind", "voice")
    PocketCommand.Stop -> JSONObject().put("kind", "stop")
    PocketCommand.NewTask -> JSONObject().put("kind", "new_task")
    PocketCommand.Approve -> JSONObject().put("kind", "approve")
    PocketCommand.Reject -> JSONObject().put("kind", "reject")
}
