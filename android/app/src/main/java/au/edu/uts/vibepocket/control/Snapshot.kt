package au.edu.uts.vibepocket.control

import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Choice
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Layer
import au.edu.uts.vibepocket.profile.Profile

data class Snapshot(
    val revision: String,
    val status: Status,
    val capabilities: Capabilities,
    val desktop: Desktop? = null,
    val observedAtMillis: Long? = null,
    val transportFresh: Boolean = true,
) {
    val activeLayer: Layer?
        get() = desktop?.profile?.layers?.firstOrNull { it.id == desktop.activeLayerId }

    fun actionFor(inputId: String, gesture: Gesture.Kind = Gesture.Kind.TAP): Action? =
        activeLayer?.bindings?.get(inputId)?.actions?.get(gesture)

    fun inputEnabled(inputId: String, gesture: Gesture.Kind = Gesture.Kind.TAP): Boolean {
        val action = actionFor(inputId, gesture)
        if (action == null) return legacyInputEnabled(inputId, gesture)
        return actionEnabled(action)
    }

    fun voiceTapEnabled(inputId: String): Boolean {
        if (!inputEnabled(inputId, Gesture.Kind.TAP)) return false
        return actionFor(inputId, Gesture.Kind.TAP)?.type == "voice" ||
            (desktop?.profile == null && inputId == "key_voice")
    }

    fun supportsHidNavigationRepeat(inputId: String, hidConnected: Boolean): Boolean {
        if (!transportFresh || !hidConnected || desktop?.question != null) return false
        val tapAction = actionFor(inputId, Gesture.Kind.TAP)
        return tapAction?.type == "navigate" &&
            inputEnabled(inputId, Gesture.Kind.TAP) &&
            actionFor(inputId, Gesture.Kind.DOUBLE_TAP) == null &&
            actionFor(inputId, Gesture.Kind.HOLD) == null
    }

    fun agentFocusEnabled(agentId: String): Boolean {
        val state = desktop ?: return false
        return canFocusAgents() && state.agents.any {
                it.id == agentId && it.freshness == Agent.Freshness.FRESH && it.actionable
            }
    }

    private fun canFocusAgents(): Boolean = desktop?.let { state ->
        capabilities.focusAgent &&
            state.tasks.availability == Tasks.Availability.FRESH &&
            state.agents.any { it.freshness == Agent.Freshness.FRESH && it.actionable }
    } == true

    private fun actionEnabled(action: Action): Boolean = when (action.type) {
        "approve" -> desktop?.let {
            capabilities.approve &&
                it.activity != Activity.THINKING &&
                it.activity != Activity.EXECUTING
        } ?: capabilities.approve
        "reject" -> capabilities.reject
        "voice" -> desktop?.voice?.available ?: capabilities.voice
        "stop" -> capabilities.stop
        "new_task" -> capabilities.newTask
        "mode_cycle" -> capabilities.modeCycle
        "model_picker" -> capabilities.modelPicker &&
            desktop?.foreground == true && desktop.question == null
        "access_cycle" -> capabilities.accessCycle && desktop?.foreground == true
        "delete_backward", "clear_input" -> capabilities.clearInput
        "focus_next", "focus_agent" -> canFocusAgents()
        "select_layer" -> desktop?.profile?.layers?.any { it.id == action.layerId } == true
        "navigate" -> capabilities.navigate && desktop?.foreground == true
        "reasoning_depth" -> desktop?.let {
            capabilities.reasoning &&
                it.foreground &&
                it.activity != Activity.THINKING &&
                it.activity != Activity.EXECUTING &&
                it.reasoning.allows(action.delta)
        } == true
        "workflow" -> capabilities.workflow && desktop?.foreground == true
        "attach" -> status.state == "ready"
        else -> false
    }

    private fun legacyInputEnabled(inputId: String, gesture: Gesture.Kind): Boolean {
        if (desktop?.profile != null || gesture != Gesture.Kind.TAP) return false
        return when (inputId) {
            "key_accept" -> capabilities.approve
            "key_reject" -> capabilities.reject
            "key_voice" -> capabilities.voice
            "key_stop" -> capabilities.stop
            "key_new_task" -> capabilities.newTask
            "key_attach" -> status.state == "ready"
            else -> false
        }
    }
}

data class Status(
    val state: String,
    val message: String?,
)

data class Capabilities(
    val voice: Boolean = false,
    val stop: Boolean = false,
    val newTask: Boolean = false,
    val approve: Boolean = false,
    val reject: Boolean = false,
    val clearInput: Boolean = false,
    val focusAgent: Boolean = false,
    val modeCycle: Boolean = false,
    val modelPicker: Boolean = false,
    val model: Boolean = false,
    val accessCycle: Boolean = false,
    val navigate: Boolean = false,
    val reasoning: Boolean = false,
    val workflow: Boolean = false,
)

data class Desktop(
    val profile: Profile?,
    val gestures: List<Gesture>,
    val choices: List<Choice>,
    val activeLayerId: String?,
    val foreground: Boolean,
    val activity: Activity,
    val agents: List<Agent>,
    val focusedAgentIndex: Int,
    val focusedAgentId: String?,
    val voice: Voice?,
    val mode: Selector,
    val access: Selector = Selector(false, ""),
    val model: Model = Model.Unavailable,
    val reasoning: Reasoning,
    val question: Question? = null,
    val tasks: Tasks = Tasks.Fresh,
    val binding: Binding = Binding.Unbound,
) {
    data class Binding(
        val state: State,
        val contextId: String?,
    ) {
        enum class State {
            CONFIRMED,
            RECONCILING,
            CONFLICT,
            UNBOUND,
        }

        companion object {
            val Unbound = Binding(State.UNBOUND, null)
        }
    }
}

data class Question(
    val index: Int,
    val count: Int,
    val header: String,
    val text: String,
    val options: List<Option>,
    val selectedOptionIndex: Int,
    val hasSpokenAnswer: Boolean,
    val isSecret: Boolean,
) {
    data class Option(
        val label: String,
        val description: String,
    )
}

data class Agent(
    val id: String,
    val label: String,
    val activity: Activity,
    val focused: Boolean,
    val freshness: Freshness = Freshness.FRESH,
    val actionable: Boolean = true,
) {
    enum class Freshness {
        FRESH,
        STALE,
    }

    internal data class Slot(
        val agent: Agent?,
        val canFocus: Boolean,
        val focused: Boolean,
    )
}

data class Tasks(
    val availability: Availability,
    val message: String?,
) {
    enum class Availability {
        FRESH,
        STALE,
        UNAVAILABLE,
    }

    companion object {
        val Fresh = Tasks(Availability.FRESH, null)
        val Unavailable = Tasks(Availability.UNAVAILABLE, null)
    }
}

internal const val MaxAgents = 24
internal val AgentId = Regex("^agent-[a-f0-9]{24}$")

internal fun Snapshot.agentSlots(): List<Agent.Slot> = desktop?.agents.orEmpty()
    .take(MaxAgents)
    .map { agent ->
        Agent.Slot(
            agent = agent,
            canFocus = agentFocusEnabled(agent.id),
            focused = agent.focused || agent.id == desktop?.focusedAgentId,
        )
    }

data class Voice(
    val available: Boolean,
    val active: Boolean,
)

data class Selector(
    val available: Boolean,
    val label: String,
    val id: String? = null,
    val options: List<Option> = emptyList(),
) {
    data class Option(
        val id: String,
        val label: String,
        val selected: Boolean,
    )
}

data class Model(
    val available: Boolean,
    val id: String?,
    val label: String,
    val options: List<Option>,
) {
    data class Option(
        val id: String,
        val label: String,
        val selected: Boolean,
    )

    companion object {
        val Unavailable = Model(false, null, "", emptyList())
    }
}

data class Reasoning(
    val available: Boolean,
    val label: String,
    val level: Level?,
    val canIncrease: Boolean,
    val canDecrease: Boolean,
    val increaseTo: Level? = null,
    val decreaseTo: Level? = null,
    val options: List<Level> = emptyList(),
) {
    enum class Level(val wireValue: String) {
        MINIMAL("minimal"),
        LOW("low"),
        MEDIUM("medium"),
        HIGH("high"),
        XHIGH("xhigh"),
        MAX("max"),
        ULTRA("ultra"),
        ;

        fun shifted(delta: Int?): Level? {
            if (delta != -1 && delta != 1) return null
            return entries.getOrNull(ordinal + delta)
        }

        val displayLabel: String
            get() = when (this) {
                MINIMAL -> "Minimal"
                LOW -> "Low"
                MEDIUM -> "Medium"
                HIGH -> "High"
                XHIGH -> "Extra high"
                MAX -> "Max"
                ULTRA -> "Ultra"
            }

        val canIncrease: Boolean get() = this != ULTRA
        val canDecrease: Boolean get() = this != MINIMAL

        companion object {
            fun fromWire(value: String?): Level? = entries.firstOrNull { it.wireValue == value }
        }
    }

    fun allows(delta: Int?): Boolean = available && when (delta) {
        1 -> canIncrease
        -1 -> canDecrease
        else -> false
    }

    fun shifted(delta: Int?): Reasoning? {
        if (!allows(delta)) return null
        val target = when (delta) {
            1 -> increaseTo
            -1 -> decreaseTo
            else -> null
        } ?: return null
        return copy(
            level = target,
            increaseTo = null,
            decreaseTo = null,
        )
    }

    companion object {
        val Unavailable = Reasoning(
            available = false,
            label = "",
            level = null,
            canIncrease = false,
            canDecrease = false,
            increaseTo = null,
            decreaseTo = null,
        )
    }
}

enum class Activity(val wireValue: String) {
    IDLE("idle"),
    UNREAD("unread"),
    THINKING("thinking"),
    EXECUTING("executing"),
    WAITING("waiting"),
    COMPLETE("complete"),
    ERROR("error"),
    ;

    companion object {
        fun fromWire(value: String): Activity = entries.firstOrNull { it.wireValue == value } ?: IDLE
    }
}
