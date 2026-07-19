package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Choice
import au.edu.uts.vibepocket.profile.FallbackInputs
import au.edu.uts.vibepocket.profile.Input

internal const val AgentChipWidthDp = 160

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
