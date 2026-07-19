package au.edu.uts.vibepocket.gesture.dial

import org.junit.Assert.assertEquals
import org.junit.Test

class RotationTest {
    @Test
    fun clockwiseQuarterTurnProducesOneIncrease() {
        val started = begin(pointerX = 0f, pointerY = -1f, centerX = 0f, centerY = 0f)

        val update = advance(started, pointerX = 1f, pointerY = 0f, centerX = 0f, centerY = 0f)

        assertEquals(1, update.step)
        assertEquals(0.0, update.state.remainderRadians, 0.0001)
    }

    @Test
    fun counterClockwiseQuarterTurnProducesOneDecrease() {
        val started = begin(pointerX = 1f, pointerY = 0f, centerX = 0f, centerY = 0f)

        val update = advance(started, pointerX = 0f, pointerY = -1f, centerX = 0f, centerY = 0f)

        assertEquals(-1, update.step)
        assertEquals(0.0, update.state.remainderRadians, 0.0001)
    }

    @Test
    fun crossingTheAngleBoundaryKeepsTheShortRotationDirection() {
        val started = begin(pointerX = -1f, pointerY = 0.1f, centerX = 0f, centerY = 0f)

        val update = advance(started, pointerX = -1f, pointerY = -0.1f, centerX = 0f, centerY = 0f)

        assertEquals(true, update.deltaRadians > 0.0)
        assertEquals(0, update.step)
    }

    @Test
    fun subThresholdMotionIsRetainedUntilTheNextMotionCompletesTheStep() {
        val started = begin(pointerX = 0f, pointerY = -1f, centerX = 0f, centerY = 0f)
        val partial = advance(started, pointerX = 1f, pointerY = -1f, centerX = 0f, centerY = 0f)

        val update = advance(partial.state, pointerX = 1f, pointerY = 0f, centerX = 0f, centerY = 0f)

        assertEquals(1, update.step)
    }

    @Test
    fun centerTouchArmsTheDialWithoutCreatingASpuriousFirstStep() {
        val started = begin(
            pointerX = 0f,
            pointerY = 0f,
            centerX = 0f,
            centerY = 0f,
            minimumRadius = 0.5f,
        )

        val armed = advance(
            started,
            pointerX = 1f,
            pointerY = 0f,
            centerX = 0f,
            centerY = 0f,
            minimumRadius = 0.5f,
        )
        val update = advance(
            armed.state,
            pointerX = 0f,
            pointerY = -1f,
            centerX = 0f,
            centerY = 0f,
            minimumRadius = 0.5f,
        )

        assertEquals(0, armed.step)
        assertEquals(-1, update.step)
    }
}
