package au.edu.uts.vibepocket.connection

import java.net.URI

data class Config(
    val baseUrl: String,
    val token: String,
) {
    private val uri = runCatching { URI(baseUrl.trim()) }
        .getOrElse { throw IllegalArgumentException("The Vibe Pocket bridge URL is invalid.") }

    init {
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
        require(token.length in 24..512 && token.none(Char::isISOControl)) {
            "The Vibe Pocket access token is invalid."
        }
    }

    val normalizedUrl: String = URI(
        "https",
        null,
        requireNotNull(uri.host).lowercase(),
        uri.port,
        null,
        null,
        null,
    ).toASCIIString()

    override fun toString(): String = "Config(baseUrl=$normalizedUrl, token=<redacted>)"
}

internal fun resolveDraft(
    saved: Config,
    baseUrl: String,
    replacementToken: String,
): Config {
    val candidateUrl = Config(baseUrl.trim(), saved.token)
    val originChanged = candidateUrl.normalizedUrl != saved.normalizedUrl
    val replacement = replacementToken.trim()
    require(!originChanged || replacement.isNotEmpty()) {
        "A new pairing token is required when the Bridge URL changes."
    }
    return Config(
        baseUrl = candidateUrl.normalizedUrl,
        token = replacement.ifEmpty { saved.token },
    )
}
