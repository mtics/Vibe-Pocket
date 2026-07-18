package au.edu.uts.vibepocket

import org.junit.Assert.assertEquals
import org.junit.Test

class DialRotationPolicyTest {
    @Test
    fun clockwiseQuarterTurnProducesOneIncrease() {
        val started = beginDialRotation(pointerX = 0f, pointerY = -1f, centerX = 0f, centerY = 0f)

        val update = advanceDialRotation(started, pointerX = 1f, pointerY = 0f, centerX = 0f, centerY = 0f)

        assertEquals(1, update.step)
        assertEquals(0.0, update.state.remainderRadians, 0.0001)
    }

    @Test
    fun counterClockwiseQuarterTurnProducesOneDecrease() {
        val started = beginDialRotation(pointerX = 1f, pointerY = 0f, centerX = 0f, centerY = 0f)

        val update = advanceDialRotation(started, pointerX = 0f, pointerY = -1f, centerX = 0f, centerY = 0f)

        assertEquals(-1, update.step)
        assertEquals(0.0, update.state.remainderRadians, 0.0001)
    }

    @Test
    fun crossingTheAngleBoundaryKeepsTheShortRotationDirection() {
        val started = beginDialRotation(pointerX = -1f, pointerY = 0.1f, centerX = 0f, centerY = 0f)

        val update = advanceDialRotation(started, pointerX = -1f, pointerY = -0.1f, centerX = 0f, centerY = 0f)

        assertEquals(true, update.deltaRadians > 0.0)
        assertEquals(0, update.step)
    }

    @Test
    fun subThresholdMotionIsRetainedUntilTheNextMotionCompletesTheStep() {
        val started = beginDialRotation(pointerX = 0f, pointerY = -1f, centerX = 0f, centerY = 0f)
        val partial = advanceDialRotation(started, pointerX = 1f, pointerY = -1f, centerX = 0f, centerY = 0f)

        val update = advanceDialRotation(partial.state, pointerX = 1f, pointerY = 0f, centerX = 0f, centerY = 0f)

        assertEquals(1, update.step)
    }
}
