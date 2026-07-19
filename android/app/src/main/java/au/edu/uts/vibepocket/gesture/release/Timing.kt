package au.edu.uts.vibepocket.gesture.release

import au.edu.uts.vibepocket.profile.Gesture

internal sealed interface Decision {
    data class Dispatch(val gesture: Gesture.Kind) : Decision
    data class DeferTap(val token: Long) : Decision
    data object None : Decision
}

internal class Timing(
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
    ): Decision {
        require(releasedAt >= downAt)
        require(longPressTimeoutMillis >= 0)

        if (holdEnabled && releasedAt - downAt >= longPressTimeoutMillis) {
            pendingTaps.remove(inputId)
            return Decision.Dispatch(Gesture.Kind.HOLD)
        }
        if (!doubleTapEnabled) {
            pendingTaps.remove(inputId)
            return if (tapEnabled) {
                Decision.Dispatch(Gesture.Kind.TAP)
            } else {
                Decision.None
            }
        }

        val previous = pendingTaps[inputId]
        if (previous != null && releasedAt - previous.releasedAt <= doubleTapTimeoutMillis) {
            pendingTaps.remove(inputId)
            return Decision.Dispatch(Gesture.Kind.DOUBLE_TAP)
        }

        pendingTaps[inputId] = PendingTap(releasedAt, tapEnabled)
        return Decision.DeferTap(releasedAt)
    }

    fun completeDeferredTap(inputId: String, token: Long): Gesture.Kind? {
        val pending = pendingTaps[inputId]?.takeIf { it.releasedAt == token } ?: return null
        pendingTaps.remove(inputId)
        return Gesture.Kind.TAP.takeIf { pending.tapEnabled }
    }
}
