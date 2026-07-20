package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Choice
import au.edu.uts.vibepocket.profile.FallbackInputs
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input

internal data class VoiceMappingIdentity(
    val layerId: String?,
    val inputId: String,
)

internal fun Snapshot.voiceMappingIdentity(inputId: String): VoiceMappingIdentity? {
    val profile = desktop?.profile
    if (profile == null) {
        return if (inputId == "key_voice") VoiceMappingIdentity(null, inputId) else null
    }
    val layer = activeLayer ?: return null
    val action = layer.bindings[inputId]?.actions?.get(Gesture.Kind.TAP)
    return if (action?.type == "voice") VoiceMappingIdentity(layer.id, inputId) else null
}

internal fun dedicatedVoiceInput(snapshot: Snapshot, activeOwnerInputId: String? = null): Input {
    val candidates = (snapshot.desktop?.profile?.inputs.orEmpty() + FallbackInputs).distinctBy(Input::id)
    if (snapshot.desktop?.voice?.active == true) {
        candidates.firstOrNull { it.id == activeOwnerInputId }?.let { return it }
    }
    candidates.firstOrNull { snapshot.voiceMappingIdentity(it.id) != null }?.let { return it }
    return candidates.first { it.id == "key_voice" }
}

internal fun unrepresentedInputs(inputs: List<Input>, representedIds: Set<String>): List<Input> =
    inputs.filterNot { it.id in representedIds }

internal fun gestureAccessibilityAction(gesture: Gesture.Kind): String? = when (gesture) {
    Gesture.Kind.DOUBLE_TAP -> "Run double-tap mapping"
    Gesture.Kind.HOLD -> "Run hold mapping"
    Gesture.Kind.TAP -> null
}

internal fun keyInputs(snapshot: Snapshot): List<Input> {
    val profile = snapshot.desktop?.profile?.inputs.orEmpty().filter { it.kind == Input.Kind.KEY }
    return (profile + FallbackInputs.filter { fallback -> profile.none { it.id == fallback.id } })
        .distinctBy(Input::id)
        .take(13)
}

internal fun inputLabel(
    action: Action?,
    input: Input,
    choices: List<Choice>,
): String = when (action?.type) {
    "approve" -> "Accept"
    "reject" -> "Reject"
    "voice" -> "Voice"
    "clear_input" -> "Clear"
    "new_task" -> "New task"
    "stop" -> "Stop"
    "mode_cycle" -> "Mode"
    "model_picker" -> "Model"
    "access_cycle" -> "Access"
    "delete_backward" -> "Delete"
    "focus_next" -> "Next agent"
    "attach" -> "Focus Codex"
    else -> choices.firstOrNull { it.action == action }?.label ?: input.label
}

internal fun reasoningInput(
    inputs: List<Input>,
    snapshot: Snapshot,
    delta: Int,
): Input? = inputs.firstOrNull { input ->
    snapshot.actionFor(input.id)?.let { action ->
        action.type == "reasoning_depth" && action.delta == delta
    } == true
}

internal fun voiceAccessibilityAction(active: Boolean): String =
    if (active) "Stop listening" else "Start listening"
