package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.control.Reasoning

internal class Prediction(
    private val nowMillis: () -> Long,
) {
    private data class Pending(
        val target: Reasoning.Level,
        val focusedAgentId: String?,
        val modelId: String?,
        val expiresAtMillis: Long,
    )

    @Volatile private var pending: Pending? = null

    fun apply(state: State, action: Action): State {
        if (action.type != "reasoning_depth") return state
        val snapshot = state.snapshot ?: return state
        val desktop = snapshot.desktop ?: return state
        val shifted = desktop.reasoning.shifted(action.delta) ?: return state
        return expect(state, requireNotNull(shifted.level))
    }

    fun expect(state: State, target: Reasoning.Level): State {
        val desktop = state.snapshot?.desktop ?: return state
        pending = Pending(
            target = target,
            focusedAgentId = desktop.focusedAgentId,
            modelId = desktop.model.id,
            expiresAtMillis = nowMillis() + ConfirmationWindowMillis,
        )
        return state.copy(reasoningTarget = target, error = null)
    }

    fun reconcile(remote: Snapshot, @Suppress("UNUSED_PARAMETER") visible: Snapshot?): Snapshot {
        val expected = pending ?: return remote
        val desktop = remote.desktop ?: run {
            pending = null
            return remote
        }
        val remoteReasoning = desktop.reasoning
        val expired = nowMillis() >= expected.expiresAtMillis
        val invalidContext = !desktop.foreground ||
            desktop.question != null ||
            desktop.focusedAgentId != expected.focusedAgentId ||
            desktop.model.id != expected.modelId
        val confirmed = remoteReasoning.available &&
            remoteReasoning.level == expected.target
        if (
            expired ||
            invalidContext ||
            confirmed ||
            !remote.capabilities.reasoning ||
            !remoteReasoning.available ||
            desktop.activity == au.edu.uts.vibepocket.control.Activity.THINKING ||
            desktop.activity == au.edu.uts.vibepocket.control.Activity.EXECUTING
        ) {
            pending = null
        }
        return remote
    }

    fun clear() {
        pending = null
    }

    fun deadlineMillis(): Long? = pending?.expiresAtMillis

    fun isPending(): Boolean = pending != null

    fun target(): Reasoning.Level? = pending?.target

    fun fail(state: State): State {
        pending = null
        return state.copy(reasoningTarget = null)
    }

    private companion object {
        const val ConfirmationWindowMillis = 3_000L
    }
}
