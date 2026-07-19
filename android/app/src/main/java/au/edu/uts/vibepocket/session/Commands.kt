package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.commandFor
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.allowsQueuedRepeat
import java.util.concurrent.atomic.AtomicLong

internal class Commands(
    private val snapshot: () -> Snapshot?,
    private val deliver: (Command, String) -> Boolean,
) {
    private val sequence = AtomicLong(0)

    fun activate(inputId: String, gesture: Gesture.Kind): Boolean {
        val current = snapshot() ?: return false
        if (!current.inputEnabled(inputId, gesture)) return false
        val repeatable = current.actionFor(inputId, gesture)?.allowsQueuedRepeat() == true
        val id = if (repeatable) {
            "input:$inputId:${gesture.wireValue}:${sequence.incrementAndGet()}"
        } else {
            "input:$inputId:${gesture.wireValue}"
        }
        return deliver(current.commandFor(inputId, gesture), id)
    }

    fun focusAgent(agentId: String): Boolean {
        val current = snapshot() ?: return false
        if (!current.agentFocusEnabled(agentId)) return false
        return deliver(Command.FocusAgent(agentId), "agent:$agentId")
    }

    fun selectLayer(layerId: String): Boolean {
        val desktop = snapshot()?.desktop ?: return false
        if (desktop.profile?.layers?.none { it.id == layerId } != false) return false
        if (desktop.activeLayerId == layerId) return false
        return deliver(Command.SelectLayer(layerId), "layer:$layerId")
    }

    fun updateBinding(
        layerId: String,
        inputId: String,
        gesture: Gesture.Kind,
        actionId: String,
    ): Boolean {
        val desktop = snapshot()?.desktop ?: return false
        val profile = desktop.profile ?: return false
        if (profile.layers.none { it.id == layerId } || profile.inputs.none { it.id == inputId }) return false
        val action = desktop.choices.firstOrNull { it.id == actionId }?.action ?: return false
        return deliver(
            Command.UpdateBinding(layerId, inputId, gesture, action),
            "mapping:$inputId:${gesture.wireValue}",
        )
    }

    fun clearBinding(layerId: String, inputId: String, gesture: Gesture.Kind): Boolean {
        val profile = snapshot()?.desktop?.profile ?: return false
        val layer = profile.layers.firstOrNull { it.id == layerId } ?: return false
        if (layer.bindings[inputId]?.actions?.containsKey(gesture) != true) return false
        return deliver(
            Command.ClearBinding(layerId, inputId, gesture),
            "mapping:$inputId:${gesture.wireValue}",
        )
    }

    fun renameLayer(layerId: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 40 || trimmed.any(Char::isISOControl)) return false
        val layer = snapshot()?.desktop?.profile?.layers?.firstOrNull { it.id == layerId } ?: return false
        if (layer.name == trimmed) return false
        return deliver(Command.RenameLayer(layerId, trimmed), "rename:$layerId")
    }

    fun updateLayerColor(layerId: String, color: String): Boolean {
        if (!color.matches(Regex("#[0-9a-fA-F]{6}"))) return false
        val layer = snapshot()?.desktop?.profile?.layers?.firstOrNull { it.id == layerId } ?: return false
        if (layer.color.equals(color, ignoreCase = true)) return false
        return deliver(Command.UpdateLayerColor(layerId, color.uppercase()), "color:$layerId")
    }

    fun updateWorkflow(workflowId: String, prompt: String): Boolean {
        val trimmed = prompt.trim()
        val invalidControl = trimmed.any { it.isISOControl() && it != '\n' && it != '\t' }
        if (trimmed.isEmpty() || trimmed.length > 4_000 || invalidControl) return false
        val workflow = snapshot()?.desktop?.profile?.workflows?.firstOrNull { it.id == workflowId } ?: return false
        if (workflow.prompt == trimmed) return false
        return deliver(Command.UpdateWorkflowPrompt(workflowId, trimmed), "workflow:$workflowId")
    }

    fun resetProfile(): Boolean {
        if (snapshot()?.desktop?.profile == null) return false
        return deliver(Command.ResetProfile, "reset-profile")
    }
}
