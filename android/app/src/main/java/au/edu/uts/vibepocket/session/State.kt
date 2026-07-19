package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.control.Snapshot

data class State(
    val config: Config? = null,
    val snapshot: Snapshot? = null,
    val isRefreshing: Boolean = false,
    val inFlightIds: Set<String> = emptySet(),
    val error: String? = null,
)

sealed interface Feedback {
    data object Success : Feedback
    data object Error : Feedback
}
