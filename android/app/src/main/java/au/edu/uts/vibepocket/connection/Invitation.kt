package au.edu.uts.vibepocket.connection

import java.net.URI
import java.net.URLDecoder

data class Invitation(
    val origin: String,
    val code: String,
) {
    companion object {
        fun parse(value: String): Invitation {
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
            require(code.matches(Regex("^[A-Za-z0-9_-]{32,128}$"))) {
                "The pairing code is invalid."
            }
            val normalizedOrigin = normalizeOrigin(origin)
            return Invitation(normalizedOrigin, code)
        }

        @Suppress("DEPRECATION")
        private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
    }
}
