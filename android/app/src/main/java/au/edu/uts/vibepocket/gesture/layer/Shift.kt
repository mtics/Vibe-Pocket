package au.edu.uts.vibepocket.gesture.layer

import au.edu.uts.vibepocket.profile.Gesture

internal const val GuardMillis = 750L

internal sealed interface Route {
    data object Pass : Route
    data object Suppress : Route
    data class Select(val layerId: String) : Route
}

internal fun route(
    inputId: String,
    gesture: Gesture.Kind,
    modifierPressed: Boolean,
    guardActive: Boolean,
): Route {
    if (guardActive) return Route.Suppress
    if (!modifierPressed || gesture != Gesture.Kind.TAP) return Route.Pass
    return Targets[inputId]?.let(Route::Select) ?: Route.Pass
}

internal fun isTarget(inputId: String): Boolean = inputId in Targets

private val Targets = mapOf(
    "key_accept" to "layer-1",
    "key_reject" to "layer-2",
    "key_voice" to "layer-3",
    "key_new_task" to "layer-4",
    "key_up" to "layer-5",
    "key_down" to "layer-6",
)
