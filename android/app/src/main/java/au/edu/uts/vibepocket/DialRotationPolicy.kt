package au.edu.uts.vibepocket

import kotlin.math.PI
import kotlin.math.atan2

internal const val DIAL_ROTATION_STEP_RADIANS = PI / 2.0

internal data class DialRotationState(
    val previousAngleRadians: Double? = null,
    val remainderRadians: Double = 0.0,
)

internal data class DialRotationUpdate(
    val state: DialRotationState,
    val step: Int = 0,
    val deltaRadians: Double = 0.0,
)

internal fun beginDialRotation(
    pointerX: Float,
    pointerY: Float,
    centerX: Float,
    centerY: Float,
): DialRotationState = DialRotationState(
    previousAngleRadians = dialAngleRadians(pointerX, pointerY, centerX, centerY),
)

internal fun advanceDialRotation(
    state: DialRotationState,
    pointerX: Float,
    pointerY: Float,
    centerX: Float,
    centerY: Float,
): DialRotationUpdate {
    val nextAngle = dialAngleRadians(pointerX, pointerY, centerX, centerY)
    val previousAngle = state.previousAngleRadians ?: return DialRotationUpdate(
        state = state.copy(previousAngleRadians = nextAngle),
    )
    val delta = normalizedAngleDelta(nextAngle - previousAngle)
    val accumulated = state.remainderRadians + delta
    val step = when {
        accumulated >= DIAL_ROTATION_STEP_RADIANS -> 1
        accumulated <= -DIAL_ROTATION_STEP_RADIANS -> -1
        else -> 0
    }
    val remainder = when (step) {
        1 -> accumulated - DIAL_ROTATION_STEP_RADIANS
        -1 -> accumulated + DIAL_ROTATION_STEP_RADIANS
        else -> accumulated
    }
    return DialRotationUpdate(
        state = DialRotationState(nextAngle, remainder),
        step = step,
        deltaRadians = delta,
    )
}

private fun dialAngleRadians(pointerX: Float, pointerY: Float, centerX: Float, centerY: Float): Double =
    atan2((pointerY - centerY).toDouble(), (pointerX - centerX).toDouble())

private fun normalizedAngleDelta(delta: Double): Double = when {
    delta > PI -> delta - (2.0 * PI)
    delta < -PI -> delta + (2.0 * PI)
    else -> delta
}
