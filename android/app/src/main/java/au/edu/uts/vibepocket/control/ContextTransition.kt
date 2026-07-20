package au.edu.uts.vibepocket.control

sealed interface ContextTransition {
    data class NewDesktop(val baselineFocusedAgentId: String?) : ContextTransition {
        init {
            require(baselineFocusedAgentId == null || AgentId.matches(baselineFocusedAgentId))
        }
    }

    data object Attached : ContextTransition

    data class Agent(val id: String) : ContextTransition {
        init {
            require(AgentId.matches(id))
        }
    }

    data class Model(val id: String) : ContextTransition {
        init {
            require(id.matches(StableSelectionId))
        }
    }

    data class Mode(val id: String) : ContextTransition

    data class Reasoning(val level: au.edu.uts.vibepocket.control.Reasoning.Level) : ContextTransition

    data class Layer(val id: String) : ContextTransition {
        init {
            require(id.length in 1..128 && id.none(Char::isISOControl))
        }
    }
}

internal fun Command.contextTransition(snapshot: Snapshot): ContextTransition? = when (this) {
    is Command.Binding -> action.contextTransition(snapshot)
    is Command.FocusAgent -> ContextTransition.Agent(agentId)
    is Command.SelectModel -> ContextTransition.Model(modelId)
    is Command.SelectMode -> ContextTransition.Mode(modeId)
    is Command.SelectReasoning -> ContextTransition.Reasoning(level)
    is Command.SelectLayer -> ContextTransition.Layer(layerId)
    Command.Attach -> ContextTransition.Attached
    Command.NewTask -> ContextTransition.NewDesktop(snapshot.desktop?.focusedAgentId)
    else -> null
}

internal fun ContextTransition.matches(snapshot: Snapshot): Boolean {
    if (!snapshot.transportFresh) return false
    val desktop = snapshot.desktop ?: return false
    return when (this) {
        is ContextTransition.NewDesktop -> {
            val focused = desktop.focusedAgentId
            focused != null && focused != baselineFocusedAgentId
        }
        ContextTransition.Attached -> desktop.foreground
        is ContextTransition.Agent -> desktop.focusedAgentId == id
        is ContextTransition.Model -> desktop.model.id == id
        is ContextTransition.Mode -> desktop.mode.id == id
        is ContextTransition.Reasoning -> desktop.reasoning.level == level
        is ContextTransition.Layer -> desktop.activeLayerId == id
    }
}

private fun au.edu.uts.vibepocket.profile.Action.contextTransition(
    snapshot: Snapshot,
): ContextTransition? = when (type) {
    "workflow", "new_task" -> ContextTransition.NewDesktop(snapshot.desktop?.focusedAgentId)
    "attach" -> ContextTransition.Attached
    "focus_next" -> {
        val desktop = snapshot.desktop ?: return null
        if (desktop.agents.isEmpty()) {
            ContextTransition.Attached
        } else {
            val nextIndex = (desktop.focusedAgentIndex + 1).mod(desktop.agents.size)
            ContextTransition.Agent(desktop.agents[nextIndex].id)
        }
    }
    "focus_agent" -> index
        ?.let { snapshot.desktop?.agents?.getOrNull(it)?.id }
        ?.let(ContextTransition::Agent)
    "select_layer" -> layerId?.let(ContextTransition::Layer)
    else -> null
}

private val StableSelectionId = Regex("^[a-zA-Z0-9._-]{1,128}$")
