package au.edu.uts.vibepocket.control

import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.allowsQueuedRepeat

sealed interface Command {
    data class Binding(
        val inputId: String,
        val gesture: Gesture.Kind = Gesture.Kind.TAP,
        val layerId: String,
        val action: Action,
    ) : Command

    data class SelectLayer(val layerId: String) : Command
    data class SelectAgent(val agentId: String) : Command {
        init {
            require(AgentId.matches(agentId))
        }
    }
    data class SelectModel(val target: TargetRef, val modelId: String) : Command {
        init {
            require(modelId.matches(Regex("^[a-zA-Z0-9._-]{1,128}$")))
        }
    }
    data class SelectMode(val target: TargetRef, val modeId: String) : Command {
        init {
            require(modeId == "default" || modeId == "plan")
        }
    }
    data class SelectReasoning(val target: TargetRef, val level: Reasoning.Level) : Command
    data class AdjustReasoning(val target: TargetRef, val delta: Int) : Command {
        init {
            require(delta == -1 || delta == 1)
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
    data object ModelPicker : Command
    data object Approve : Command
    data object Reject : Command
}

internal fun Snapshot.inputAllowsQueuedRepeat(inputId: String): Boolean =
    actionFor(inputId, Gesture.Kind.TAP)?.allowsQueuedRepeat() == true

internal fun Snapshot.commandFor(
    inputId: String,
    gesture: Gesture.Kind,
): Command {
    if (desktop?.profile != null) {
        return Command.Binding(
            inputId = inputId,
            gesture = gesture,
            layerId = requireNotNull(desktop.activeLayerId),
            action = requireNotNull(actionFor(inputId, gesture)),
        )
    }
    return when (inputId) {
        "key_accept" -> Command.Approve
        "key_reject" -> Command.Reject
        "key_voice" -> Command.Voice
        "key_stop" -> Command.Stop
        "key_new_task" -> Command.NewTask
        "key_attach" -> Command.Attach
        else -> error("A legacy snapshot cannot safely resolve this controller gesture.")
    }
}
