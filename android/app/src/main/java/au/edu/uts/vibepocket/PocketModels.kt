package au.edu.uts.vibepocket

import java.net.URI

data class ConnectionConfig(
    val baseUrl: String,
    val token: String,
) {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Vibe Pocket requires an HTTPS bridge URL."
        }
        require(token.length >= 24) { "The Vibe Pocket access token is invalid." }
    }

    val normalizedUrl: String = baseUrl.trimEnd('/')
}

data class PocketSnapshot(
    val revision: String,
    val status: BridgeStatus,
    val controls: DesktopControls,
)

data class BridgeStatus(
    val state: String,
    val message: String?,
)

data class DesktopControls(
    val voice: Boolean,
    val stop: Boolean,
    val newTask: Boolean,
    val approve: Boolean,
    val reject: Boolean,
)

sealed interface PocketCommand {
    data object Attach : PocketCommand
    data object Voice : PocketCommand
    data object Stop : PocketCommand
    data object NewTask : PocketCommand
    data object Approve : PocketCommand
    data object Reject : PocketCommand
}

data class PocketUiState(
    val config: ConnectionConfig? = null,
    val snapshot: PocketSnapshot? = null,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

class BridgeException(message: String) : IllegalStateException(message)
