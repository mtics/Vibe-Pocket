package au.edu.uts.vibepocket.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URLEncoder
import java.time.Instant

class InvitationTest {
    @Test
    fun parsesPairingDeepLink() {
        val origin = URLEncoder.encode("https://M5.example.ts.net", Charsets.UTF_8)
        val code = "a".repeat(43)
        val expiresAt = URLEncoder.encode("2099-01-01T00:05:00Z", Charsets.UTF_8)

        assertEquals(
            Invitation("https://m5.example.ts.net", code, Instant.parse("2099-01-01T00:05:00Z").toEpochMilli()),
            Invitation.parse("vibepocket://pair?origin=$origin&code=$code&expiresAt=$expiresAt") { 0L },
        )
    }

    @Test
    fun rejectsUnexpectedSchemesAndWeakCodes() {
        assertThrows(IllegalArgumentException::class.java) {
            Invitation.parse("https://example.test/pair?code=${"a".repeat(43)}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Invitation.parse("vibepocket://pair?origin=https%3A%2F%2Fm5.example.ts.net&code=short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Invitation.parse(
                "vibepocket://pair?origin=https%3A%2F%2Fm5.example.ts.net&origin=https%3A%2F%2Fevil.test&code=${"a".repeat(43)}",
            )
        }
    }

    @Test
    fun rejectsMissingMalformedAndExpiredExpiry() {
        val prefix = "vibepocket://pair?origin=https%3A%2F%2Fm5.example.ts.net&code=${"a".repeat(43)}"

        assertThrows(IllegalArgumentException::class.java) { Invitation.parse(prefix) { 0L } }
        assertThrows(IllegalArgumentException::class.java) {
            Invitation.parse("$prefix&expiresAt=tomorrow") { 0L }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Invitation.parse("$prefix&expiresAt=1970-01-01T00%3A00%3A01Z") { 1_000L }
        }
    }
}
