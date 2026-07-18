package au.edu.uts.vibepocket

import org.junit.Assert.assertEquals
import org.junit.Test

class LayerShiftPolicyTest {
    @Test
    fun modifierRoutesSixMicroLayerChordsWithoutLeakingTheCommand() {
        assertEquals(
            LayerShiftRoute.Select("layer-3"),
            routeLayerShift("key_voice", ControllerGesture.TAP, modifierPressed = true, guardActive = false),
        )
        assertEquals(
            LayerShiftRoute.Select("layer-6"),
            routeLayerShift("key_down", ControllerGesture.TAP, modifierPressed = true, guardActive = false),
        )
    }

    @Test
    fun guardSuppressesEveryInputAndOtherGesturesPassNormally() {
        assertEquals(
            LayerShiftRoute.Suppress,
            routeLayerShift("key_accept", ControllerGesture.TAP, modifierPressed = false, guardActive = true),
        )
        assertEquals(
            LayerShiftRoute.Pass,
            routeLayerShift("key_voice", ControllerGesture.HOLD, modifierPressed = true, guardActive = false),
        )
    }

    @Test
    fun onlyTheSixMicroChordKeysAreCapturedAsLayerTargets() {
        assertEquals(true, isLayerShiftTarget("key_accept"))
        assertEquals(true, isLayerShiftTarget("key_down"))
        assertEquals(false, isLayerShiftTarget("key_stop"))
        assertEquals(false, isLayerShiftTarget("touch"))
    }
}
