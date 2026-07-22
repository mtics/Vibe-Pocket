package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.control.Desktop
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.TargetRef
import au.edu.uts.vibepocket.profile.Action

internal sealed interface SettingTarget {
    data class Model(val id: String) : SettingTarget
    data class ReasoningLevel(val level: Reasoning.Level) : SettingTarget
}

internal class Prediction(
    private val nowMillis: () -> Long,
) {
    sealed interface Observation {
        data object Pending : Observation
        data class Confirmed(val predictionId: Long) : Observation
        data class Conflict(val predictionId: Long, val message: String) : Observation
        data object None : Observation
    }

    data class Deadline(
        val predictionId: Long,
        val expiresAtMillis: Long,
    )

    data class Timeout(
        val predictionId: Long,
        val message: String,
    )

    private data class Pending(
        val id: Long,
        val target: SettingTarget,
        val targetRef: TargetRef,
        val baselineModelId: String?,
        val baselineReasoningLevel: Reasoning.Level?,
        val acknowledged: Boolean,
        val expiresAtMillis: Long?,
        val minimumObservationVersion: Long?,
    )

    private val lock = Any()
    private var nextId = 0L
    private var pending: Pending? = null

    fun apply(state: State, action: Action): State = synchronized(lock) {
        if (action.type != "reasoning_depth") return state
        val reasoning = state.snapshot?.desktop?.reasoning ?: return state
        val shifted = reasoning.shifted(action.delta)?.level ?: return state
        beginLocked(state, SettingTarget.ReasoningLevel(shifted))
    }

    fun beginModel(
        state: State,
        modelId: String,
        requiresAcknowledgement: Boolean = false,
    ): State = synchronized(lock) {
        beginLocked(state, SettingTarget.Model(modelId), requiresAcknowledgement)
    }

    fun beginReasoning(
        state: State,
        level: Reasoning.Level,
        requiresAcknowledgement: Boolean = false,
    ): State = synchronized(lock) {
        beginLocked(state, SettingTarget.ReasoningLevel(level), requiresAcknowledgement)
    }

    fun expect(state: State, target: Reasoning.Level): State = synchronized(lock) {
        beginLocked(state, SettingTarget.ReasoningLevel(target))
    }

    fun acknowledge(): Deadline? = synchronized(lock) {
        val expected = pending ?: return null
        val expiresAt = nowMillis() + ConfirmationWindowMillis
        pending = expected.copy(acknowledged = true, expiresAtMillis = expiresAt)
        Deadline(expected.id, expiresAt)
    }

    fun reconcile(remote: Snapshot, @Suppress("UNUSED_PARAMETER") visible: Snapshot?): Snapshot = remote

    fun observeAfter(version: Long) = synchronized(lock) {
        pending = pending?.copy(minimumObservationVersion = version)
    }

    fun observe(remote: Snapshot, version: Long): Observation = synchronized(lock) {
        val expected = pending ?: return Observation.None
        if (!expected.acknowledged) return Observation.Pending
        val minimumVersion = expected.minimumObservationVersion
        if (minimumVersion == AwaitingObservation || minimumVersion != null && version < minimumVersion) {
            return Observation.Pending
        }
        if (!remote.transportFresh || !remote.sources.appServer.fresh) return Observation.Pending
        val desktop = remote.desktop
            ?: return conflict(expected, "The desktop task changed before the setting was confirmed.")
        if (!sameContext(desktop, expected)) {
            return conflict(expected, "The desktop task changed before the setting was confirmed.")
        }

        return when (val target = expected.target) {
            is SettingTarget.Model -> when {
                !remote.capabilities.model || !desktop.model.available -> Observation.Pending
                desktop.model.available && desktop.model.id == target.id -> confirm(expected)
                desktop.model.id != expected.baselineModelId ->
                    conflict(expected, "The model changed to a different value before confirmation.")
                else -> Observation.Pending
            }
            is SettingTarget.ReasoningLevel -> when {
                desktop.model.id != expected.baselineModelId ->
                    conflict(expected, "The model changed while reasoning was being adjusted.")
                !remote.capabilities.reasoning || !desktop.reasoning.available -> Observation.Pending
                desktop.reasoning.available && desktop.reasoning.level == target.level -> confirm(expected)
                desktop.reasoning.level != expected.baselineReasoningLevel ->
                    conflict(expected, "Reasoning changed to a different value before confirmation.")
                else -> Observation.Pending
            }
        }
    }

    fun clear() = synchronized(lock) {
        pending = null
    }

    fun deadline(): Deadline? = synchronized(lock) {
        pending?.expiresAtMillis?.let { Deadline(requireNotNull(pending).id, it) }
    }

    fun currentId(): Long? = synchronized(lock) { pending?.id }

    fun isPending(): Boolean = synchronized(lock) { pending != null }

    fun target(): Reasoning.Level? = synchronized(lock) {
        (pending?.target as? SettingTarget.ReasoningLevel)?.level
    }

    fun modelTarget(): String? = synchronized(lock) {
        (pending?.target as? SettingTarget.Model)?.id
    }

    fun present(state: State): State = synchronized(lock) { presentLocked(state) }

    fun fail(state: State): State = synchronized(lock) {
        pending = null
        presentLocked(state)
    }

    fun timeout(predictionId: Long): Timeout? = synchronized(lock) {
        val expected = pending?.takeIf { it.id == predictionId } ?: return null
        val name = when (expected.target) {
            is SettingTarget.Model -> "Model"
            is SettingTarget.ReasoningLevel -> "Reasoning"
        }
        pending = null
        Timeout(expected.id, "$name change could not be confirmed.")
    }

    private fun beginLocked(
        state: State,
        target: SettingTarget,
        requiresAcknowledgement: Boolean = false,
    ): State {
        if (pending != null) return state
        val desktop = state.snapshot?.desktop ?: return state
        val targetRef = desktop.binding.target.boundRef ?: return state
        val acknowledged = !requiresAcknowledgement
        pending = Pending(
            id = ++nextId,
            target = target,
            targetRef = targetRef,
            baselineModelId = desktop.model.id,
            baselineReasoningLevel = desktop.reasoning.level,
            acknowledged = acknowledged,
            expiresAtMillis = if (acknowledged) nowMillis() + ConfirmationWindowMillis else null,
            minimumObservationVersion = AwaitingObservation,
        )
        return presentLocked(state).copy(error = null)
    }

    private fun presentLocked(state: State): State = state.copy(
        modelTarget = (pending?.target as? SettingTarget.Model)?.id,
        reasoningTarget = (pending?.target as? SettingTarget.ReasoningLevel)?.level,
    )

    private fun sameContext(desktop: Desktop, expected: Pending): Boolean =
        desktop.question == null &&
            desktop.binding.target.boundRef == expected.targetRef

    private fun confirm(expected: Pending): Observation {
        pending = null
        return Observation.Confirmed(expected.id)
    }

    private fun conflict(expected: Pending, message: String): Observation {
        pending = null
        return Observation.Conflict(expected.id, message)
    }

    private companion object {
        const val ConfirmationWindowMillis = 3_000L
        const val AwaitingObservation = Long.MAX_VALUE
    }
}
