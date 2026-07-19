package au.edu.uts.vibepocket.gesture.layer

import au.edu.uts.vibepocket.profile.Gesture
import org.junit.Assert.assertEquals
import org.junit.Test

class ShiftTest {
    @Test
    fun modifierRoutesSixMicroLayerChordsWithoutLeakingTheCommand() {
        assertEquals(
            Route.Select("layer-3"),
            route("key_voice", Gesture.Kind.TAP, modifierPressed = true, guardActive = false),
        )
        assertEquals(
            Route.Select("layer-6"),
            route("key_down", Gesture.Kind.TAP, modifierPressed = true, guardActive = false),
        )
    }

    @Test
    fun guardSuppressesEveryInputAndOtherGesturesPassNormally() {
        assertEquals(
            Route.Suppress,
            route("key_accept", Gesture.Kind.TAP, modifierPressed = false, guardActive = true),
        )
        assertEquals(
            Route.Pass,
            route("key_voice", Gesture.Kind.HOLD, modifierPressed = true, guardActive = false),
        )
    }

    @Test
    fun onlyTheSixMicroChordKeysAreCapturedAsLayerTargets() {
        assertEquals(true, isTarget("key_accept"))
        assertEquals(true, isTarget("key_down"))
        assertEquals(false, isTarget("key_stop"))
        assertEquals(false, isTarget("touch"))
    }
}
