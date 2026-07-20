package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.connection.PendingCommand
import au.edu.uts.vibepocket.control.ContextTransition
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.matches

internal sealed interface TransitionBarrier {
    data object Unconfirmed : TransitionBarrier

    sealed interface Pending : TransitionBarrier {
        val uiId: String
        val target: ContextTransition
    }

    data class AwaitingDelivery(
        override val uiId: String,
        override val target: ContextTransition,
        val command: PendingCommand? = null,
    ) : Pending

    data class AwaitingObservation(
        override val uiId: String,
        override val target: ContextTransition,
        val command: PendingCommand,
        val requiredRefreshVersion: Long? = null,
    ) : Pending

    data class Confirmed(
        override val uiId: String,
        override val target: ContextTransition,
        val command: PendingCommand,
    ) : Pending
}

internal val TransitionBarrier.isPending: Boolean
    get() = this is TransitionBarrier.Pending

internal fun TransitionBarrier.deliveryAccepted(command: PendingCommand): TransitionBarrier = when (this) {
    is TransitionBarrier.AwaitingDelivery -> {
        val sameCommand = this.command == null || this.command.operationId == command.operationId
        if (uiId == command.uiId && target == command.transition && sameCommand) {
            TransitionBarrier.AwaitingObservation(uiId, target, command)
        } else {
            this
        }
    }
    else -> this
}

internal fun TransitionBarrier.owns(command: PendingCommand): Boolean = when (this) {
    is TransitionBarrier.AwaitingObservation -> this.command.operationId == command.operationId
    is TransitionBarrier.Confirmed -> this.command.operationId == command.operationId
    else -> false
}

internal fun TransitionBarrier.observationPrepared(version: Long): TransitionBarrier = when (this) {
    is TransitionBarrier.AwaitingObservation -> {
        if (requiredRefreshVersion == null) copy(requiredRefreshVersion = version) else this
    }
    else -> this
}

internal fun TransitionBarrier.observe(snapshot: Snapshot, version: Long): TransitionBarrier = when (this) {
    is TransitionBarrier.AwaitingObservation -> {
        val required = requiredRefreshVersion
        if (required != null && version >= required && target.matches(snapshot)) {
            TransitionBarrier.Confirmed(uiId, target, command)
        } else {
            this
        }
    }
    else -> this
}

internal fun TransitionBarrier.unconfirm(
    uiId: String,
    target: ContextTransition?,
): TransitionBarrier {
    val pending = this as? TransitionBarrier.Pending ?: return this
    if (pending.uiId != uiId || target != null && pending.target != target) return this
    return TransitionBarrier.Unconfirmed
}

internal fun TransitionBarrier.confirmedCommand(): PendingCommand? =
    (this as? TransitionBarrier.Confirmed)?.command

internal fun TransitionBarrier.confirmationCleared(command: PendingCommand): TransitionBarrier = when (this) {
    is TransitionBarrier.Confirmed -> {
        if (this.command.operationId == command.operationId) TransitionBarrier.Unconfirmed else this
    }
    else -> this
}
