package au.edu.uts.vibepocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionConfigTest {
    private val saved = ConnectionConfig(
        "https://M5.example.test/",
        "0123456789abcdefghijklmn",
    )

    @Test
    fun sameOriginBlankTokenKeepsTheStoredSecret() {
        val resolved = resolveConnectionDraft(saved, "https://m5.example.test", "")

        assertEquals("https://m5.example.test", resolved.normalizedUrl)
        assertEquals(saved.token, resolved.token)
    }

    @Test
    fun changedOriginRequiresAReplacementToken() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            resolveConnectionDraft(saved, "https://other.example.test", "")
        }

        assertTrue(error.message.orEmpty().contains("new pairing token"))
    }

    @Test
    fun changedOriginUsesOnlyTheReplacementToken() {
        val replacement = "zyxwvutsrqponmlkjihgfedc"
        val resolved = resolveConnectionDraft(saved, "https://other.example.test", replacement)

        assertEquals("https://other.example.test", resolved.normalizedUrl)
        assertEquals(replacement, resolved.token)
    }

    @Test
    fun bridgeUrlRejectsCredentialsPathsQueriesAndFragments() {
        listOf(
            "https://user@m5.example.test",
            "https://m5.example.test/custom",
            "https://m5.example.test?target=other",
            "https://m5.example.test#fragment",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                ConnectionConfig(url, saved.token)
            }
        }
    }

    @Test
    fun tokenRejectsControlCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionConfig("https://m5.example.test", "0123456789abcdefghijklmn\n")
        }
    }

    @Test
    fun stringRepresentationRedactsTheToken() {
        val rendered = saved.toString()

        assertTrue(rendered.contains("<redacted>"))
        assertFalse(rendered.contains(saved.token))
    }
}
