package au.edu.uts.vibepocket.gesture.release

import au.edu.uts.vibepocket.profile.Gesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimingTest {
    @Test
    fun tapDispatchesImmediatelyWhenDoubleTapIsNotMapped() {
        val arbiter = Timing(DOUBLE_TAP_TIMEOUT)

        assertEquals(
            Decision.Dispatch(Gesture.Kind.TAP),
            arbiter.release("up", 100, 180, tapEnabled = true, doubleTapEnabled = false, holdEnabled = true, LONG_PRESS_TIMEOUT),
        )
    }

    @Test
    fun firstTapDefersAndCompletesOnlyAfterItsToken() {
        val arbiter = Timing(DOUBLE_TAP_TIMEOUT)

        assertEquals(
            Decision.DeferTap(180),
            arbiter.release("up", 100, 180, tapEnabled = true, doubleTapEnabled = true, holdEnabled = false, LONG_PRESS_TIMEOUT),
        )
        assertNull(arbiter.completeDeferredTap("up", 179))
        assertEquals(Gesture.Kind.TAP, arbiter.completeDeferredTap("up", 180))
        assertNull(arbiter.completeDeferredTap("up", 180))
    }

    @Test
    fun secondTapInsideWindowUpgradesToDoubleTap() {
        val arbiter = Timing(DOUBLE_TAP_TIMEOUT)
        arbiter.release("up", 100, 180, tapEnabled = true, doubleTapEnabled = true, holdEnabled = true, LONG_PRESS_TIMEOUT)

        assertEquals(
            Decision.Dispatch(Gesture.Kind.DOUBLE_TAP),
            arbiter.release("up", 300, 360, tapEnabled = true, doubleTapEnabled = true, holdEnabled = true, LONG_PRESS_TIMEOUT),
        )
        assertNull(arbiter.completeDeferredTap("up", 180))
    }

    @Test
    fun holdCancelsAnUnresolvedTapOnTheSameDirection() {
        val arbiter = Timing(DOUBLE_TAP_TIMEOUT)
        arbiter.release("up", 100, 180, tapEnabled = true, doubleTapEnabled = true, holdEnabled = true, LONG_PRESS_TIMEOUT)

        assertEquals(
            Decision.Dispatch(Gesture.Kind.HOLD),
            arbiter.release("up", 250, 800, tapEnabled = true, doubleTapEnabled = true, holdEnabled = true, LONG_PRESS_TIMEOUT),
        )
        assertNull(arbiter.completeDeferredTap("up", 180))
    }

    @Test
    fun doubleTapWorksWithoutASingleTapBinding() {
        val arbiter = Timing(DOUBLE_TAP_TIMEOUT)
        arbiter.release("up", 100, 180, tapEnabled = false, doubleTapEnabled = true, holdEnabled = false, LONG_PRESS_TIMEOUT)

        assertEquals(
            Decision.Dispatch(Gesture.Kind.DOUBLE_TAP),
            arbiter.release("up", 300, 360, tapEnabled = false, doubleTapEnabled = true, holdEnabled = false, LONG_PRESS_TIMEOUT),
        )
    }

    @Test
    fun lateSecondTapStartsANewIndependentWindow() {
        val arbiter = Timing(DOUBLE_TAP_TIMEOUT)
        arbiter.release("up", 100, 180, tapEnabled = true, doubleTapEnabled = true, holdEnabled = false, LONG_PRESS_TIMEOUT)

        assertEquals(
            Decision.DeferTap(700),
            arbiter.release("up", 620, 700, tapEnabled = true, doubleTapEnabled = true, holdEnabled = false, LONG_PRESS_TIMEOUT),
        )
        assertNull(arbiter.completeDeferredTap("up", 180))
        assertEquals(Gesture.Kind.TAP, arbiter.completeDeferredTap("up", 700))
    }

    @Test
    fun pendingTapsAreIsolatedByDirection() {
        val arbiter = Timing(DOUBLE_TAP_TIMEOUT)
        arbiter.release("up", 100, 180, tapEnabled = true, doubleTapEnabled = true, holdEnabled = false, LONG_PRESS_TIMEOUT)
        arbiter.release("left", 200, 280, tapEnabled = true, doubleTapEnabled = true, holdEnabled = false, LONG_PRESS_TIMEOUT)

        assertEquals(Gesture.Kind.TAP, arbiter.completeDeferredTap("up", 180))
        assertEquals(Gesture.Kind.TAP, arbiter.completeDeferredTap("left", 280))
    }

    private companion object {
        const val DOUBLE_TAP_TIMEOUT = 300L
        const val LONG_PRESS_TIMEOUT = 500L
    }
}
