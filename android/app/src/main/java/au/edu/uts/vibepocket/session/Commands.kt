package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.TargetRef
import au.edu.uts.vibepocket.control.commandFor
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.allowsQueuedRepeat
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest

internal class Commands(
    private val snapshot: () -> Snapshot?,
    private val deliver: (Command, String) -> Boolean,
) {
    private val sequence = AtomicLong(0)

    fun activate(inputId: String, gesture: Gesture.Kind): Boolean {
        val current = snapshot() ?: return false
        if (!current.inputEnabled(inputId, gesture)) return false
        val action = current.actionFor(inputId, gesture)
        val repeatable = action?.allowsQueuedRepeat() == true
        val id = if (repeatable) {
            "input:$inputId:${gesture.wireValue}:${sequence.incrementAndGet()}"
        } else {
            "input:$inputId:${gesture.wireValue}"
        }
        if (action?.type == "reasoning_depth") {
            val desktop = current.desktop ?: return false
            val target = desktop.binding.target.boundRef ?: return false
            val delta = action.delta ?: return false
            val level = desktop.reasoning.shifted(delta)?.level ?: return false
            return deliver(
                Command.SelectReasoning(target, level),
                target.uiId("reasoning", level.wireValue),
            )
        }
        return deliver(current.commandFor(inputId, gesture), id)
    }

    fun openModel(): Boolean {
        val current = snapshot() ?: return false
        val desktop = current.desktop ?: return false
        if (!desktop.foreground || desktop.question != null || !current.capabilities.modelPicker) return false
        return deliver(Command.ModelPicker, "model-picker")
    }

    fun selectAgent(agentId: String): Boolean {
        val current = snapshot() ?: return false
        if (!current.agentFocusEnabled(agentId)) return false
        return deliver(Command.SelectAgent(agentId), "agent:$agentId")
    }

    fun selectModel(modelId: String): Boolean {
        val current = snapshot() ?: return false
        if (!current.modelSelectionEnabled(modelId)) return false
        val target = current.desktop?.binding?.target?.boundRef ?: return false
        return deliver(Command.SelectModel(target, modelId), target.uiId("model", modelId))
    }

    fun selectMode(modeId: String): Boolean {
        return false
    }

    fun selectReasoning(level: au.edu.uts.vibepocket.control.Reasoning.Level): Boolean {
        val current = snapshot() ?: return false
        if (!current.reasoningSelectionEnabled(level)) return false
        val target = current.desktop?.binding?.target?.boundRef ?: return false
        return deliver(
            Command.SelectReasoning(target, level),
            target.uiId("reasoning", level.wireValue),
        )
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

private fun TargetRef.uiId(kind: String, value: String): String =
    "$kind:${identityDigest()}:$value"

private fun TargetRef.identityDigest(): String {
    val identity = listOf(
        threadId,
        agentId,
        bindingEpoch.toString(),
        bridgeInstanceId,
        appServerGeneration.toString(),
        canonicalWorkspaceId,
    ).joinToString("\u001f")
    return MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
