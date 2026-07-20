package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.Invitation
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.ConflictGroup

data class State(
    val config: Config? = null,
    val invitation: Invitation? = null,
    val snapshot: Snapshot? = null,
    val reasoningTarget: Reasoning.Level? = null,
    val isRefreshing: Boolean = false,
    val inFlightIds: Set<String> = emptySet(),
    val busyGroups: Set<ConflictGroup> = emptySet(),
    val operation: Operation? = null,
    val contextTransitionPending: Boolean = false,
    val error: String? = null,
)

sealed interface Feedback {
    data object Success : Feedback
    data object Error : Feedback
}
