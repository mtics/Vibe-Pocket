package au.edu.uts.vibepocket

internal sealed interface GestureReleaseDecision {
    data class Dispatch(val gesture: ControllerGesture) : GestureReleaseDecision
    data class DeferTap(val token: Long) : GestureReleaseDecision
    data object None : GestureReleaseDecision
}

internal class GestureReleaseArbiter(
    private val doubleTapTimeoutMillis: Long,
) {
    private data class PendingTap(
        val releasedAt: Long,
        val tapEnabled: Boolean,
    )

    private val pendingTaps = mutableMapOf<String, PendingTap>()

    init {
        require(doubleTapTimeoutMillis >= 0)
    }

    fun release(
        inputId: String,
        downAt: Long,
        releasedAt: Long,
        tapEnabled: Boolean,
        doubleTapEnabled: Boolean,
        holdEnabled: Boolean,
        longPressTimeoutMillis: Long,
    ): GestureReleaseDecision {
        require(releasedAt >= downAt)
        require(longPressTimeoutMillis >= 0)

        if (holdEnabled && releasedAt - downAt >= longPressTimeoutMillis) {
            pendingTaps.remove(inputId)
            return GestureReleaseDecision.Dispatch(ControllerGesture.HOLD)
        }
        if (!doubleTapEnabled) {
            pendingTaps.remove(inputId)
            return if (tapEnabled) {
                GestureReleaseDecision.Dispatch(ControllerGesture.TAP)
            } else {
                GestureReleaseDecision.None
            }
        }

        val previous = pendingTaps[inputId]
        if (previous != null && releasedAt - previous.releasedAt <= doubleTapTimeoutMillis) {
            pendingTaps.remove(inputId)
            return GestureReleaseDecision.Dispatch(ControllerGesture.DOUBLE_TAP)
        }

        pendingTaps[inputId] = PendingTap(releasedAt, tapEnabled)
        return GestureReleaseDecision.DeferTap(releasedAt)
    }

    fun completeDeferredTap(inputId: String, token: Long): ControllerGesture? {
        val pending = pendingTaps[inputId]?.takeIf { it.releasedAt == token } ?: return null
        pendingTaps.remove(inputId)
        return ControllerGesture.TAP.takeIf { pending.tapEnabled }
    }
}
