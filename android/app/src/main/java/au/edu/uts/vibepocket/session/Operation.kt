package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.connection.PendingCommand

data class Operation(
    val id: String,
    val uiId: String,
    val phase: Phase,
    val message: String? = null,
) {
    enum class Phase {
        QUEUED,
        SENT,
        ACKNOWLEDGED,
        OBSERVING,
        OBSERVED,
        UNKNOWN,
        FAILED,
    }
}

internal fun PendingCommand.operation(
    phase: Operation.Phase = when (this.phase) {
        PendingCommand.Phase.PREPARED -> Operation.Phase.QUEUED
        PendingCommand.Phase.DISPATCH_ATTEMPTED -> Operation.Phase.OBSERVING
        PendingCommand.Phase.ACKNOWLEDGED -> Operation.Phase.ACKNOWLEDGED
    },
    message: String? = null,
): Operation = Operation(operationId, uiId, phase, message)
