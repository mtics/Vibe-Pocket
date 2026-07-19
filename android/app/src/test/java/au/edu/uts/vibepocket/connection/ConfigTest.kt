package au.edu.uts.vibepocket.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {
    private val saved = Config(
        "https://M5.example.test/",
        "0123456789abcdefghijklmn",
    )

    @Test
    fun originIsCanonicalizedIndependentlyOfTheCredential() {
        assertEquals("https://m5.example.test", saved.normalizedUrl)
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
                Config(url, saved.credential)
            }
        }
    }

    @Test
    fun credentialRejectsControlCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            Config("https://m5.example.test", "0123456789abcdefghijklmn\n")
        }
    }

    @Test
    fun stringRepresentationRedactsTheCredential() {
        val rendered = saved.toString()

        assertTrue(rendered.contains("<redacted>"))
        assertFalse(rendered.contains(saved.credential))
    }
}
