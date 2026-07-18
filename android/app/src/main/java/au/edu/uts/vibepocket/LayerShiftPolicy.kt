package au.edu.uts.vibepocket

internal const val LAYER_SHIFT_GUARD_MILLIS = 750L

internal sealed interface LayerShiftRoute {
    data object Pass : LayerShiftRoute
    data object Suppress : LayerShiftRoute
    data class Select(val layerId: String) : LayerShiftRoute
}

internal fun routeLayerShift(
    inputId: String,
    gesture: ControllerGesture,
    modifierPressed: Boolean,
    guardActive: Boolean,
): LayerShiftRoute {
    if (guardActive) return LayerShiftRoute.Suppress
    if (!modifierPressed || gesture != ControllerGesture.TAP) return LayerShiftRoute.Pass
    return LayerShiftTargets[inputId]?.let(LayerShiftRoute::Select) ?: LayerShiftRoute.Pass
}

internal fun isLayerShiftTarget(inputId: String): Boolean = inputId in LayerShiftTargets

private val LayerShiftTargets = mapOf(
    "key_accept" to "layer-1",
    "key_reject" to "layer-2",
    "key_voice" to "layer-3",
    "key_new_task" to "layer-4",
    "key_up" to "layer-5",
    "key_down" to "layer-6",
)
