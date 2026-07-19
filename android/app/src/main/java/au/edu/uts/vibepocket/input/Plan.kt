package au.edu.uts.vibepocket.input

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.hid.Mapping
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Gesture

internal sealed interface Plan {
    data object Disabled : Plan

    data class Bridge(
        val inputId: String,
        val gesture: Gesture.Kind,
    ) : Plan

    data class HidTap(
        val action: Action,
        val fallback: Bridge,
    ) : Plan

    data class HidHold(
        val action: Action,
        val fallback: Bridge,
    ) : Plan
}

private val HidTapActions = setOf(
    "approve",
    "stop",
    "mode_cycle",
    "model_picker",
    "delete_backward",
    "clear_input",
    "navigate",
    "reasoning_depth",
)

internal fun activation(
    snapshot: Snapshot?,
    inputId: String,
    gesture: Gesture.Kind,
): Plan {
    if (snapshot == null || !snapshot.inputEnabled(inputId, gesture)) return Plan.Disabled
    val fallback = Plan.Bridge(inputId, gesture)
    if (!snapshot.transportFresh) return fallback
    val action = resolve(snapshot, inputId, gesture) ?: return fallback
    val desktop = snapshot.desktop
    val useHid = desktop?.foreground == true &&
        desktop.question == null &&
        action.type in HidTapActions &&
        Mapping.chords(action) != null
    return if (useHid) Plan.HidTap(action, fallback) else fallback
}

internal fun voicePress(snapshot: Snapshot?, inputId: String): Plan {
    if (snapshot == null || !snapshot.voiceTapEnabled(inputId)) return Plan.Disabled
    val fallback = Plan.Bridge(inputId, Gesture.Kind.TAP)
    if (!snapshot.transportFresh) return fallback
    val action = resolve(snapshot, inputId, Gesture.Kind.TAP) ?: return fallback
    val desktop = snapshot.desktop
    return if (
        desktop?.foreground == true &&
        desktop.question == null &&
        action.type == "voice" &&
        Mapping.chords(action)?.size == 1
    ) {
        Plan.HidHold(action, fallback)
    } else {
        fallback
    }
}

private fun resolve(
    snapshot: Snapshot,
    inputId: String,
    gesture: Gesture.Kind,
): Action? = snapshot.actionFor(inputId, gesture)
    ?: if (gesture == Gesture.Kind.TAP && snapshot.desktop?.profile == null) {
        when (inputId) {
            "key_accept" -> Action("approve")
            "key_reject" -> Action("reject")
            "key_voice" -> Action("voice")
            "key_stop" -> Action("stop")
            "key_mode" -> Action("mode_cycle")
            "key_clear" -> Action("delete_backward")
            "key_up" -> Action("navigate", direction = "up")
            "key_down" -> Action("navigate", direction = "down")
            "key_left" -> Action("navigate", direction = "left")
            "key_right" -> Action("navigate", direction = "right")
            else -> null
        }
    } else {
        null
    }
