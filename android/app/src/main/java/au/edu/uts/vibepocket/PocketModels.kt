package au.edu.uts.vibepocket

import org.json.JSONObject
import java.net.URI

data class ConnectionConfig(
    val baseUrl: String,
    val token: String,
) {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Vibe Pocket requires an HTTPS bridge URL."
        }
        require(token.length >= 24) { "The Vibe Pocket access token is invalid." }
    }

    val normalizedUrl: String = baseUrl.trimEnd('/')
}

data class PocketSnapshot(
    val revision: String,
    val status: BridgeStatus,
    val controls: DesktopControls,
    val controller: ControllerState? = null,
) {
    val activeLayer: ControllerLayer?
        get() = controller?.profile?.layers?.firstOrNull { it.id == controller.activeLayerId }

    fun actionFor(inputId: String, gesture: ControllerGesture = ControllerGesture.TAP): ControllerAction? =
        activeLayer?.bindings?.get(inputId)?.actions?.get(gesture)

    fun inputEnabled(inputId: String, gesture: ControllerGesture = ControllerGesture.TAP): Boolean {
        val action = actionFor(inputId, gesture)
        if (action == null) return legacyInputEnabled(inputId, gesture)
        return actionEnabled(action)
    }

    fun voiceTapEnabled(inputId: String): Boolean {
        if (!inputEnabled(inputId, ControllerGesture.TAP)) return false
        return actionFor(inputId, ControllerGesture.TAP)?.type == "voice" ||
            (controller?.profile == null && inputId == "key_voice")
    }

    fun supportsHidNavigationRepeat(inputId: String, hidConnected: Boolean): Boolean {
        if (!hidConnected || controller?.userInput != null) return false
        val tapAction = actionFor(inputId, ControllerGesture.TAP)
        return tapAction?.type == "navigate" &&
            inputEnabled(inputId, ControllerGesture.TAP) &&
            actionFor(inputId, ControllerGesture.DOUBLE_TAP) == null &&
            actionFor(inputId, ControllerGesture.HOLD) == null
    }

    fun agentFocusEnabled(agentId: String): Boolean {
        val state = controller ?: return false
        return controls.focusAgent && state.agents.any { it.id == agentId }
    }

    private fun actionEnabled(action: ControllerAction): Boolean = when (action.type) {
        "approve" -> controls.approve
        "reject" -> controls.reject
        "voice" -> controller?.voice?.available ?: controls.voice
        "stop" -> controls.stop
        "new_task" -> controls.newTask
        "mode_cycle" -> controls.modeCycle && controller?.desktopFocused == true
        "access_cycle" -> controls.accessCycle && controller?.desktopFocused == true
        "clear_input" -> controls.clearInput
        "focus_next" -> controls.focusAgent || status.state == "ready"
        "focus_agent" -> controls.focusAgent
        "select_layer" -> controller?.profile?.layers?.any { it.id == action.layerId } == true
        "navigate" -> controls.navigate && controller?.desktopFocused == true
        "reasoning_depth" -> controls.reasoning && controller?.desktopFocused == true &&
            controller.reasoning.allows(action.delta)
        "workflow" -> controls.workflow && controller?.desktopFocused == true
        "attach" -> status.state == "ready"
        else -> false
    }

    private fun legacyInputEnabled(inputId: String, gesture: ControllerGesture): Boolean {
        if (controller?.profile != null || gesture != ControllerGesture.TAP) return false
        return when (inputId) {
            "key_accept" -> controls.approve
            "key_reject" -> controls.reject
            "key_voice" -> controls.voice
            "key_stop" -> controls.stop
            "key_new_task" -> controls.newTask
            "key_attach" -> status.state == "ready"
            else -> false
        }
    }
}

/** The controller deck need not rebuild for a revision or status-message-only update. */
internal fun PocketSnapshot.hasSameControllerSurface(other: PocketSnapshot): Boolean =
    status.state == other.status.state &&
        controls == other.controls &&
        controller == other.controller

data class BridgeStatus(
    val state: String,
    val message: String?,
)

data class DesktopControls(
    val voice: Boolean = false,
    val stop: Boolean = false,
    val newTask: Boolean = false,
    val approve: Boolean = false,
    val reject: Boolean = false,
    val clearInput: Boolean = false,
    val focusAgent: Boolean = false,
    val modeCycle: Boolean = false,
    val accessCycle: Boolean = false,
    val navigate: Boolean = false,
    val reasoning: Boolean = false,
    val workflow: Boolean = false,
)

data class ControllerState(
    val profile: ControllerProfile?,
    val gestures: List<GestureOption>,
    val actionCatalog: List<ActionCatalogEntry>,
    val activeLayerId: String?,
    val desktopFocused: Boolean,
    val taskState: TaskState,
    val agents: List<AgentStatus>,
    val focusedAgentIndex: Int,
    val focusedAgentId: String?,
    val voice: VoiceStatus?,
    val mode: SelectorStatus,
    val access: SelectorStatus = SelectorStatus(false, ""),
    val reasoning: ReasoningStatus,
    val userInput: CodexQuestion? = null,
)

data class CodexQuestion(
    val questionIndex: Int,
    val questionCount: Int,
    val header: String,
    val question: String,
    val options: List<CodexQuestionOption>,
    val selectedOptionIndex: Int,
    val hasSpokenAnswer: Boolean,
    val isSecret: Boolean,
)

data class CodexQuestionOption(
    val label: String,
    val description: String,
)

data class ControllerProfile(
    val version: Int,
    val inputs: List<ControllerInput>,
    val workflows: List<ControllerWorkflow>,
    val layers: List<ControllerLayer>,
)

data class ControllerInput(
    val id: String,
    val kind: InputKind,
    val label: String,
    val icon: String,
)

enum class InputKind(val wireValue: String) {
    KEY("key"),
    TOUCH("touch"),
    JOYSTICK("joystick"),
    DIAL("dial"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String): InputKind = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

enum class ControllerGesture(val wireValue: String, val shortLabel: String) {
    TAP("tap", "T"),
    DOUBLE_TAP("double_tap", "2x"),
    HOLD("hold", "H"),
    ;

    companion object {
        fun fromWire(value: String): ControllerGesture? = entries.firstOrNull { it.wireValue == value }
    }
}

data class GestureOption(
    val gesture: ControllerGesture,
    val label: String,
)

data class ControllerWorkflow(
    val id: String,
    val label: String,
    val prompt: String,
)

data class ControllerLayer(
    val id: String,
    val name: String,
    val color: String?,
    val bindings: Map<String, BindingDescriptor>,
)

data class BindingDescriptor(
    val actions: Map<ControllerGesture, ControllerAction>,
)

data class ControllerAction(
    val type: String,
    val direction: String? = null,
    val delta: Int? = null,
    val index: Int? = null,
    val workflowId: String? = null,
    val layerId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("type", type).also { root ->
        direction?.let { root.put("direction", it) }
        delta?.let { root.put("delta", it) }
        index?.let { root.put("index", it) }
        workflowId?.let { root.put("workflowId", it) }
        layerId?.let { root.put("layerId", it) }
    }
}

/** Physical controls that represent a sequence of discrete steps, not one idempotent command. */
internal fun ControllerAction.allowsQueuedRepeat(): Boolean = type in setOf(
    "navigate",
    "mode_cycle",
    "access_cycle",
    "reasoning_depth",
)

internal fun PocketSnapshot.inputAllowsQueuedRepeat(inputId: String): Boolean =
    actionFor(inputId, ControllerGesture.TAP)?.allowsQueuedRepeat() == true

data class ActionCatalogEntry(
    val id: String,
    val label: String,
    val action: ControllerAction,
)

data class AgentStatus(
    val id: String,
    val label: String,
    val state: TaskState,
    val focused: Boolean,
)

data class VoiceStatus(
    val available: Boolean,
    val active: Boolean,
)

internal data class AgentSlot(
    val agent: AgentStatus?,
    val canFocus: Boolean,
    val focused: Boolean,
)

internal val AgentIdPattern = Regex("^agent-[a-f0-9]{24}$")

internal fun PocketSnapshot.agentSlots(slotCount: Int = 6): List<AgentSlot> {
    require(slotCount >= 0)
    val agents = controller?.agents.orEmpty()
    return List(slotCount) { index ->
        val agent = agents.getOrNull(index)
        AgentSlot(
            agent = agent,
            canFocus = agent?.let { agentFocusEnabled(it.id) } == true,
            focused = agent?.let { it.focused || it.id == controller?.focusedAgentId } == true,
        )
    }
}

data class SelectorStatus(
    val available: Boolean,
    val label: String,
)

enum class ReasoningLevel(val wireValue: String) {
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    ;

    companion object {
        fun fromWire(value: String?): ReasoningLevel? = entries.firstOrNull { it.wireValue == value }
    }
}

data class ReasoningStatus(
    val available: Boolean,
    val label: String,
    val level: ReasoningLevel?,
    val canIncrease: Boolean,
    val canDecrease: Boolean,
) {
    fun allows(delta: Int?): Boolean = available && when (delta) {
        1 -> canIncrease
        -1 -> canDecrease
        else -> false
    }

    companion object {
        val Unavailable = ReasoningStatus(
            available = false,
            label = "",
            level = null,
            canIncrease = false,
            canDecrease = false,
        )
    }
}

enum class TaskState(val wireValue: String) {
    IDLE("idle"),
    UNREAD("unread"),
    THINKING("thinking"),
    EXECUTING("executing"),
    WAITING("waiting"),
    COMPLETE("complete"),
    ERROR("error"),
    ;

    companion object {
        fun fromWire(value: String): TaskState = entries.firstOrNull { it.wireValue == value } ?: IDLE
    }
}

sealed interface PocketCommand {
    data class Binding(
        val inputId: String,
        val gesture: ControllerGesture = ControllerGesture.TAP,
    ) : PocketCommand

    data class SelectLayer(val layerId: String) : PocketCommand
    data class FocusAgent(val agentId: String) : PocketCommand {
        init {
            require(AgentIdPattern.matches(agentId))
        }
    }
    data class UpdateBinding(
        val layerId: String,
        val inputId: String,
        val gesture: ControllerGesture,
        val action: ControllerAction,
    ) : PocketCommand

    data class ClearBinding(
        val layerId: String,
        val inputId: String,
        val gesture: ControllerGesture,
    ) : PocketCommand

    data class RenameLayer(val layerId: String, val name: String) : PocketCommand
    data class UpdateLayerColor(val layerId: String, val color: String) : PocketCommand
    data class UpdateWorkflowPrompt(val workflowId: String, val prompt: String) : PocketCommand
    data object ResetProfile : PocketCommand
    data object Attach : PocketCommand
    data object Voice : PocketCommand
    data object VoiceStart : PocketCommand
    data object VoiceStop : PocketCommand
    data object Stop : PocketCommand
    data object NewTask : PocketCommand
    data object Approve : PocketCommand
    data object Reject : PocketCommand
}

data class PocketUiState(
    val config: ConnectionConfig? = null,
    val snapshot: PocketSnapshot? = null,
    val isRefreshing: Boolean = false,
    val inFlightIds: Set<String> = emptySet(),
    val error: String? = null,
)

sealed interface PocketFeedback {
    data object Success : PocketFeedback
    data object Error : PocketFeedback
}

class BridgeException(message: String) : IllegalStateException(message)

internal val FallbackKeyInputs = listOf(
    ControllerInput("key_accept", InputKind.KEY, "Accept", "check"),
    ControllerInput("key_reject", InputKind.KEY, "Reject", "close"),
    ControllerInput("key_voice", InputKind.KEY, "Voice", "mic"),
    ControllerInput("key_new_task", InputKind.KEY, "New task", "add"),
    ControllerInput("key_stop", InputKind.KEY, "Stop", "stop"),
    ControllerInput("key_mode", InputKind.KEY, "Mode", "cycle"),
    ControllerInput("key_clear", InputKind.KEY, "Clear", "clear"),
    ControllerInput("key_focus", InputKind.KEY, "Next agent", "agent"),
    ControllerInput("key_up", InputKind.KEY, "Up", "up"),
    ControllerInput("key_down", InputKind.KEY, "Down", "down"),
    ControllerInput("key_left", InputKind.KEY, "Left", "left"),
    ControllerInput("key_right", InputKind.KEY, "Right", "right"),
    ControllerInput("key_attach", InputKind.KEY, "Focus Codex", "focus"),
)

internal fun PocketSnapshot.commandForInput(
    inputId: String,
    gesture: ControllerGesture,
): PocketCommand {
    if (controller?.profile != null) return PocketCommand.Binding(inputId, gesture)
    if (gesture != ControllerGesture.TAP) return PocketCommand.Binding(inputId, gesture)
    return when (inputId) {
        "key_accept" -> PocketCommand.Approve
        "key_reject" -> PocketCommand.Reject
        "key_voice" -> PocketCommand.Voice
        "key_stop" -> PocketCommand.Stop
        "key_new_task" -> PocketCommand.NewTask
        "key_attach" -> PocketCommand.Attach
        else -> PocketCommand.Binding(inputId, gesture)
    }
}
