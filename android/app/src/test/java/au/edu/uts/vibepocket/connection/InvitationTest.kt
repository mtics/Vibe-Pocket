package au.edu.uts.vibepocket.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URLEncoder

class InvitationTest {
    @Test
    fun parsesPairingDeepLink() {
        val origin = URLEncoder.encode("https://M5.example.ts.net", Charsets.UTF_8)
        val code = "a".repeat(43)

        assertEquals(
            Invitation("https://m5.example.ts.net", code),
            Invitation.parse("vibepocket://pair?origin=$origin&code=$code"),
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
}
