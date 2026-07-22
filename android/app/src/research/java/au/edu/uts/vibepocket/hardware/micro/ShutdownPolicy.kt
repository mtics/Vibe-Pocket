package au.edu.uts.vibepocket.hardware.micro

internal data class ProcessBoundary(
    val releaseClaim: Boolean,
    val requestRecovery: Boolean,
    val stopService: Boolean,
)

internal enum class RecoveryDecision {
    BUSY,
    RETRY,
    REQUEST,
    EXIT_PROCESS,
}

internal class ShutdownPolicy {
    private enum class Phase {
        ACTIVE,
        STOPPING,
        FINISHED,
    }

    private var phase = Phase.ACTIVE
    private var stopWhenReady = false

    var recoveryPending = false
        private set

    val stopping: Boolean get() = phase != Phase.ACTIVE
    val finished: Boolean get() = phase == Phase.FINISHED

    fun begin(requestStop: Boolean): Boolean {
        stopWhenReady = stopWhenReady || requestStop
        if (stopping) return false
        phase = Phase.STOPPING
        return true
    }

    fun complete(claimed: Boolean, gattAttempted: Boolean): ProcessBoundary? {
        if (phase != Phase.STOPPING) return null
        phase = Phase.FINISHED
        val requestRecovery = claimed && gattAttempted
        return ProcessBoundary(
            releaseClaim = claimed && !gattAttempted,
            requestRecovery = requestRecovery,
            stopService = stopWhenReady && !requestRecovery,
        )
    }

    fun restoreOnStop(claimed: Boolean): Boolean = finished && claimed

    fun prepareRecovery(available: Boolean): RecoveryDecision = when {
        recoveryPending -> RecoveryDecision.BUSY
        !available -> RecoveryDecision.RETRY
        else -> {
            recoveryPending = true
            RecoveryDecision.REQUEST
        }
    }

    fun recoveryResult(ready: Boolean): RecoveryDecision {
        recoveryPending = false
        return if (ready) RecoveryDecision.EXIT_PROCESS else RecoveryDecision.RETRY
    }

    fun recoverySubmissionFailed(): RecoveryDecision {
        recoveryPending = false
        return RecoveryDecision.RETRY
    }
}
