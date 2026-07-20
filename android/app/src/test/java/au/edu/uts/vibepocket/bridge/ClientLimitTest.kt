package au.edu.uts.vibepocket.bridge

import au.edu.uts.vibepocket.connection.Invitation
import java.io.ByteArrayInputStream
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClientLimitTest {
    @Test
    fun currentProtocolClaimRequiresCommandRecoveryAndBindingContext() {
        val invitation = Invitation("https://bridge.example.test", "a".repeat(43))
        val response = JSONObject()
            .put("protocolVersion", ProtocolVersion)
            .put(
                "capabilities",
                JSONArray(listOf("device_credentials", "pairing_commit", "command_results", "binding_context")),
            )
            .put("credentialState", "pending")
            .put("credentialExpiresAt", "2099-01-01T00:05:00Z")
            .put("baseUrl", invitation.origin)
            .put("token", "vp1.phone123.abcdefghijklmnopqrstuvwxyzABCDEF")

        val issued = decodePairingClaim(invitation, response)

        assertEquals("vp1.phone123.abcdefghijklmnopqrstuvwxyzABCDEF", issued.config.credential)
        assertEquals(Instant.parse("2099-01-01T00:05:00Z").toEpochMilli(), issued.expiresAtMillis)
    }

    @Test
    fun incompatibleClaimNamesBothProtocolsAndTheRecoveryAction() {
        val invitation = Invitation("https://bridge.example.test", "a".repeat(43))
        val response = JSONObject().put("protocolVersion", ProtocolVersion - 2)

        val failure = assertThrows(Failure::class.java) { decodePairingClaim(invitation, response) }

        assertEquals(
            "The Bridge uses pairing protocol ${ProtocolVersion - 2}, but this app requires " +
                "$ProtocolVersion. Update Vibe Pocket Bridge Host and try again.",
            failure.message,
        )
    }

    @Test
    fun currentProtocolClaimRejectsActiveOrIncompleteCapabilities() {
        val invitation = Invitation("https://bridge.example.test", "a".repeat(43))
        val response = JSONObject()
            .put("protocolVersion", ProtocolVersion)
            .put("capabilities", JSONArray(listOf("device_credentials")))
            .put("credentialState", "active")
            .put("credentialExpiresAt", "2099-01-01T00:05:00Z")
            .put("baseUrl", invitation.origin)
            .put("token", "vp1.phone123.abcdefghijklmnopqrstuvwxyzABCDEF")

        assertThrows(Failure::class.java) { decodePairingClaim(invitation, response) }
        response.put(
            "capabilities",
            JSONArray(listOf("device_credentials", "pairing_commit", "command_results", "binding_context")),
        )
        assertThrows(Failure::class.java) { decodePairingClaim(invitation, response) }

        response.put("credentialState", "pending")
        response.put("capabilities", JSONArray(listOf("device_credentials", "pairing_commit")))
        assertThrows(Failure::class.java) { decodePairingClaim(invitation, response) }

        response.put(
            "capabilities",
            JSONArray(listOf("device_credentials", "pairing_commit", "command_results")),
        )
        assertThrows(Failure::class.java) { decodePairingClaim(invitation, response) }

        response.put(
            "capabilities",
            JSONArray(listOf("device_credentials", "pairing_commit", "binding_context")),
        )
        assertThrows(Failure::class.java) { decodePairingClaim(invitation, response) }
    }

    @Test
    fun declaredOversizeResponseIsRejectedBeforeReading() {
        val input = object : ByteArrayInputStream(byteArrayOf()) {
            override fun read(): Int = error("The oversized body must not be read.")
        }

        assertThrows(Failure::class.java) {
            readResponse(input, contentLength = 9, limit = 8)
        }
    }

    @Test
    fun streamingOversizeResponseIsRejectedWithoutContentLength() {
        assertThrows(Failure::class.java) {
            readResponse(ByteArrayInputStream(ByteArray(9)), contentLength = -1, limit = 8)
        }
    }

    @Test
    fun boundedEventParserDeliversSnapshotAndId() {
        val ids = mutableListOf<String>()
        var changes = 0

        consumeEvents(
            input = ByteArrayInputStream("id: 42\nevent: snapshot_changed\n\n".toByteArray()),
            onEventId = ids::add,
            onSnapshotChanged = { changes += 1 },
        )

        assertEquals(listOf("42"), ids)
        assertEquals(1, changes)
    }

    @Test
    fun endlessEventLineIsRejectedAtTheByteLimit() {
        val input = ByteArrayInputStream(ByteArray(MaxEventLineBytes + 1) { 'a'.code.toByte() })

        assertThrows(Failure::class.java) {
            consumeEvents(input, onEventId = {}, onSnapshotChanged = {})
        }
    }
}
