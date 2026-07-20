package au.edu.uts.vibepocket.bridge

import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.control.Command
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ClientCommandTest {
    @Test
    fun snapshotRejectsMissingOrIncompatibleProtocolVersion() {
        val responses = listOf(
            JSONObject().put("revision", "r_missing"),
            JSONObject().put("protocolVersion", ProtocolVersion + 1).put("revision", "r_future"),
        )

        responses.forEach { response ->
            val client = Http { url ->
                assertEquals("/v1/pocket/snapshot", url.path)
                FakeConnection(url, HttpURLConnection.HTTP_OK, response.toString())
            }

            val failure = assertThrows(Failure::class.java) {
                runBlocking { client.snapshot(ConfigValue) }
            }

            assertEquals(
                "The Vibe Pocket bridge returned an incompatible snapshot protocol version.",
                failure.message,
            )
        }
    }

    @Test
    fun commandUsesExplicitOperationIdAndRequiresHttp200() = runBlocking {
        val operationId = UUID.randomUUID().toString()
        lateinit var connection: FakeConnection
        val client = Http { url ->
            assertEquals("/v1/pocket/commands", url.path)
            FakeConnection(url, HttpURLConnection.HTTP_OK, result("succeeded")).also { connection = it }
        }

        client.command(ConfigValue, Command.Approve, operationId)

        assertEquals(operationId, connection.getRequestProperty("Idempotency-Key"))
        assertEquals("POST", connection.requestMethod)
        assertEquals("approve", JSONObject(connection.requestBody()).getString("kind"))

        val created = Http { url -> FakeConnection(url, HttpURLConnection.HTTP_CREATED, "{}") }
        val failure = assertThrows(Failure::class.java) {
            runBlocking { created.command(ConfigValue, Command.Approve, operationId) }
        }
        assertEquals(HttpURLConnection.HTTP_CREATED, failure.statusCode)
    }

    @Test
    fun commandResultUsesSameOperationIdAndMaps404() = runBlocking {
        val operationId = UUID.randomUUID().toString()
        val client = Http { url ->
            assertEquals("/v1/pocket/commands/$operationId", url.path)
            FakeConnection(url, HttpURLConnection.HTTP_NOT_FOUND, error("not visible"))
        }

        assertSame(CommandResult.NotFound, client.commandResult(ConfigValue, operationId))
    }

    @Test
    fun commandResultDecodesAllProtocolStates() {
        val expected = mapOf(
            "accepted" to CommandStatus.ACCEPTED,
            "running" to CommandStatus.RUNNING,
            "succeeded" to CommandStatus.SUCCEEDED,
            "failed" to CommandStatus.FAILED,
            "unknown" to CommandStatus.UNKNOWN,
        )

        expected.forEach { (wire, status) ->
            assertEquals(status, decodeCommandResult(JSONObject().put("status", wire)).status)
        }
    }

    @Test
    fun commandRejectsTerminalFailureInsideHttp200() {
        val operationId = UUID.randomUUID().toString()
        val client = Http { url ->
            FakeConnection(
                url,
                HttpURLConnection.HTTP_OK,
                result("unknown", "command_outcome_indeterminate", 409),
            )
        }

        val failure = assertThrows(Failure::class.java) {
            runBlocking { client.command(ConfigValue, Command.Approve, operationId) }
        }

        assertEquals(409, failure.statusCode)
        assertEquals("command_outcome_indeterminate", failure.errorCode)
    }

    @Test
    fun voiceStopRejectsTerminalFailureInsideHttp200() {
        val operationId = UUID.randomUUID().toString()
        val client = Http { url ->
            FakeConnection(
                url,
                HttpURLConnection.HTTP_OK,
                result("failed", "desktop_action_failed", 409),
            )
        }

        val failure = assertThrows(Failure::class.java) {
            runBlocking { client.stopVoice(ConfigValue, operationId) }
        }

        assertEquals("desktop_action_failed", failure.errorCode)
    }

    @Test
    fun structuredServerErrorCodeIsPreserved() {
        val failure = decodeFailure(error("policy_rejected"), 409)

        assertEquals(409, failure.statusCode)
        assertEquals("policy_rejected", failure.errorCode)
        assertEquals("Rejected by policy.", failure.message)
    }

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val response: String,
    ) : HttpURLConnection(url) {
        private val output = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = response.toByteArray().size.toLong()
        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = ByteArrayInputStream(response.toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(response.toByteArray())

        fun requestBody(): String = output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        val ConfigValue = Config("https://bridge.example.test", "0123456789abcdefghijklmn")

        fun error(code: String): String = JSONObject()
            .put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("message", "Rejected by policy."),
            )
            .toString()

        fun result(status: String, code: String? = null, errorStatus: Int? = null): String = JSONObject()
            .put("status", status)
            .apply {
                if (code != null) {
                    put(
                        "error",
                        JSONObject()
                            .put("code", code)
                            .put("message", "The operation did not complete.")
                            .apply { errorStatus?.let { put("status", it) } },
                    )
                }
            }
            .toString()
    }
}
