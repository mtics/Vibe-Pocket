package au.edu.uts.vibepocket.gesture.dial

import kotlin.math.PI
import kotlin.math.atan2

internal const val StepRadians = PI / 2.0
internal const val DeadZoneFraction = 0.22f

internal data class State(
    val previousAngleRadians: Double? = null,
    val remainderRadians: Double = 0.0,
)

internal data class Update(
    val state: State,
    val step: Int = 0,
    val deltaRadians: Double = 0.0,
)

internal fun begin(
    pointerX: Float,
    pointerY: Float,
    centerX: Float,
    centerY: Float,
    minimumRadius: Float = 0f,
): State = State(
    previousAngleRadians = dialAngleRadians(pointerX, pointerY, centerX, centerY, minimumRadius),
)

internal fun advance(
    state: State,
    pointerX: Float,
    pointerY: Float,
    centerX: Float,
    centerY: Float,
    minimumRadius: Float = 0f,
): Update {
    val nextAngle = dialAngleRadians(pointerX, pointerY, centerX, centerY, minimumRadius)
        ?: return Update(state = state)
    val previousAngle = state.previousAngleRadians ?: return Update(
        state = state.copy(previousAngleRadians = nextAngle),
    )
    val delta = normalizedAngleDelta(nextAngle - previousAngle)
    val accumulated = state.remainderRadians + delta
    val step = when {
        accumulated >= StepRadians -> 1
        accumulated <= -StepRadians -> -1
        else -> 0
    }
    val remainder = when (step) {
        1 -> accumulated - StepRadians
        -1 -> accumulated + StepRadians
        else -> accumulated
    }
    return Update(
        state = State(nextAngle, remainder),
        step = step,
        deltaRadians = delta,
    )
}

private fun dialAngleRadians(
    pointerX: Float,
    pointerY: Float,
    centerX: Float,
    centerY: Float,
    minimumRadius: Float,
): Double? {
    require(minimumRadius >= 0f)
    val deltaX = pointerX - centerX
    val deltaY = pointerY - centerY
    if ((deltaX * deltaX) + (deltaY * deltaY) < minimumRadius * minimumRadius) return null
    return atan2(deltaY.toDouble(), deltaX.toDouble())
}

private fun normalizedAngleDelta(delta: Double): Double = when {
    delta > PI -> delta - (2.0 * PI)
    delta < -PI -> delta + (2.0 * PI)
    else -> delta
}
