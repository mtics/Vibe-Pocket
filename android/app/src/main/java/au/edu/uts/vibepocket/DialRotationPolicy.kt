package au.edu.uts.vibepocket

import kotlin.math.PI
import kotlin.math.atan2

internal const val DIAL_ROTATION_STEP_RADIANS = PI / 2.0
internal const val DIAL_ROTATION_DEAD_ZONE_FRACTION = 0.22f

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
    minimumRadius: Float = 0f,
): DialRotationState = DialRotationState(
    previousAngleRadians = dialAngleRadians(pointerX, pointerY, centerX, centerY, minimumRadius),
)

internal fun advanceDialRotation(
    state: DialRotationState,
    pointerX: Float,
    pointerY: Float,
    centerX: Float,
    centerY: Float,
    minimumRadius: Float = 0f,
): DialRotationUpdate {
    val nextAngle = dialAngleRadians(pointerX, pointerY, centerX, centerY, minimumRadius)
        ?: return DialRotationUpdate(state = state)
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
