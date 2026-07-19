package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Action

internal class Prediction(
    private val nowMillis: () -> Long,
) {
    private data class Pending(
        val status: au.edu.uts.vibepocket.control.Reasoning,
        val expiresAtMillis: Long,
    )

    @Volatile private var pending: Pending? = null

    fun apply(state: State, action: Action): State {
        if (action.type != "reasoning_depth") return state
        val snapshot = state.snapshot ?: return state
        val desktop = snapshot.desktop ?: return state
        val shifted = desktop.reasoning.shifted(action.delta) ?: return state
        pending = Pending(
            status = shifted,
            expiresAtMillis = nowMillis() + ConfirmationWindowMillis,
        )
        return state.copy(
            snapshot = snapshot.copy(desktop = desktop.copy(reasoning = shifted)),
            error = null,
        )
    }

    fun reconcile(remote: Snapshot, visible: Snapshot?): Snapshot {
        val expected = pending ?: return remote
        val remoteReasoning = remote.desktop?.reasoning
        if (
            nowMillis() >= expected.expiresAtMillis ||
            remoteReasoning?.available != true ||
            remoteReasoning.level == expected.status.level
        ) {
            pending = null
            return remote
        }
        val optimistic = visible?.desktop?.reasoning
            ?.takeIf { it.level == expected.status.level }
            ?: expected.status
        val desktop = requireNotNull(remote.desktop)
        return remote.copy(desktop = desktop.copy(reasoning = optimistic))
    }

    fun clear() {
        pending = null
    }

    private companion object {
        const val ConfirmationWindowMillis = 3_000L
    }
}
