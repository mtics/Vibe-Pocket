package au.edu.uts.vibepocket.connection

import java.net.URI
import java.net.URLDecoder
import java.time.Instant

data class Invitation(
    val origin: String,
    val code: String,
    val expiresAtMillis: Long = Long.MAX_VALUE,
) {
    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis

    companion object {
        fun parse(
            value: String,
            nowMillis: () -> Long = System::currentTimeMillis,
        ): Invitation {
            val uri = runCatching { URI(value.trim()) }
                .getOrElse { throw IllegalArgumentException("The pairing invitation is invalid.") }
            require(uri.scheme == "vibepocket" && uri.host == "pair" && uri.path.isNullOrEmpty()) {
                "The pairing invitation is invalid."
            }
            val parameters = mutableMapOf<String, String>()
            uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).forEach { entry ->
                val parts = entry.split('=', limit = 2)
                val key = decode(parts[0])
                require(parameters.put(key, decode(parts.getOrElse(1) { "" })) == null) {
                    "The pairing invitation contains duplicate fields."
                }
            }
            val origin = parameters["origin"] ?: throw IllegalArgumentException("The pairing address is missing.")
            val code = parameters["code"] ?: throw IllegalArgumentException("The pairing code is missing.")
            val expiresAt = parameters["expiresAt"]
                ?: throw IllegalArgumentException("The pairing expiry is missing.")
            require(code.matches(Regex("^[A-Za-z0-9_-]{32,128}$"))) {
                "The pairing code is invalid."
            }
            val expiresAtMillis = runCatching { Instant.parse(expiresAt).toEpochMilli() }
                .getOrElse { throw IllegalArgumentException("The pairing expiry is invalid.") }
            require(nowMillis() < expiresAtMillis) { "This pairing invitation has expired." }
            val normalizedOrigin = normalizeOrigin(origin)
            return Invitation(normalizedOrigin, code, expiresAtMillis)
        }

        @Suppress("DEPRECATION")
        private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
    }
}
