package au.edu.uts.vibepocket.connection

import java.net.URI

data class Config(
    val baseUrl: String,
    val credential: String,
) {
    val normalizedUrl: String = normalizeOrigin(baseUrl)

    init {
        require(credential.length in 24..512 && credential.none(Char::isISOControl)) {
            "The Vibe Pocket device credential is invalid."
        }
    }

    override fun toString(): String = "Config(baseUrl=$normalizedUrl, credential=<redacted>)"
}

internal fun normalizeOrigin(value: String): String {
    val uri = runCatching { URI(value.trim()) }
        .getOrElse { throw IllegalArgumentException("The Vibe Pocket bridge URL is invalid.") }
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "Vibe Pocket requires an HTTPS bridge URL."
    }
    require(
        uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    ) {
        "Use only the Bridge HTTPS origin, without a path, query, fragment, or user info."
    }
    return URI(
        "https",
        null,
        requireNotNull(uri.host).lowercase(),
        uri.port,
        null,
        null,
        null,
    ).toASCIIString()
}
