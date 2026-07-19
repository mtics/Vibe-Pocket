package au.edu.uts.vibepocket.control

import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.allowsQueuedRepeat

sealed interface Command {
    data class Binding(
        val inputId: String,
        val gesture: Gesture.Kind = Gesture.Kind.TAP,
    ) : Command

    data class SelectLayer(val layerId: String) : Command
    data class FocusAgent(val agentId: String) : Command {
        init {
            require(AgentId.matches(agentId))
        }
    }
    data class UpdateBinding(
        val layerId: String,
        val inputId: String,
        val gesture: Gesture.Kind,
        val action: Action,
    ) : Command

    data class ClearBinding(
        val layerId: String,
        val inputId: String,
        val gesture: Gesture.Kind,
    ) : Command

    data class RenameLayer(val layerId: String, val name: String) : Command
    data class UpdateLayerColor(val layerId: String, val color: String) : Command
    data class UpdateWorkflowPrompt(val workflowId: String, val prompt: String) : Command
    data object ResetProfile : Command
    data object Attach : Command
    data object Voice : Command
    data object VoiceStart : Command
    data object VoiceStop : Command
    data object Stop : Command
    data object NewTask : Command
    data object Approve : Command
    data object Reject : Command
}

internal fun Snapshot.inputAllowsQueuedRepeat(inputId: String): Boolean =
    actionFor(inputId, Gesture.Kind.TAP)?.allowsQueuedRepeat() == true

internal fun Snapshot.commandFor(
    inputId: String,
    gesture: Gesture.Kind,
): Command {
    if (desktop?.profile != null) return Command.Binding(inputId, gesture)
    if (gesture != Gesture.Kind.TAP) return Command.Binding(inputId, gesture)
    return when (inputId) {
        "key_accept" -> Command.Approve
        "key_reject" -> Command.Reject
        "key_voice" -> Command.Voice
        "key_stop" -> Command.Stop
        "key_new_task" -> Command.NewTask
        "key_attach" -> Command.Attach
        else -> Command.Binding(inputId, gesture)
    }
}
