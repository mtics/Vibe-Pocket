package au.edu.uts.vibepocket.profile

data class Profile(
    val version: Int,
    val inputs: List<Input>,
    val workflows: List<Workflow>,
    val layers: List<Layer>,
)

data class Input(
    val id: String,
    val kind: Kind,
    val label: String,
    val icon: String,
) {
    enum class Kind(val wireValue: String) {
        KEY("key"),
        TOUCH("touch"),
        JOYSTICK("joystick"),
        DIAL("dial"),
        UNKNOWN("unknown"),
        ;

        companion object {
            fun fromWire(value: String): Kind = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
        }
    }
}

data class Gesture(
    val kind: Kind,
    val label: String,
) {
    enum class Kind(val wireValue: String, val shortLabel: String) {
        TAP("tap", "T"),
        DOUBLE_TAP("double_tap", "2x"),
        HOLD("hold", "H"),
        ;

        companion object {
            fun fromWire(value: String): Kind? = entries.firstOrNull { it.wireValue == value }
        }
    }
}

data class Workflow(
    val id: String,
    val label: String,
    val prompt: String,
)

data class Layer(
    val id: String,
    val name: String,
    val color: String?,
    val bindings: Map<String, Binding>,
)

data class Binding(
    val actions: Map<Gesture.Kind, Action>,
)

data class Action(
    val type: String,
    val direction: String? = null,
    val delta: Int? = null,
    val index: Int? = null,
    val workflowId: String? = null,
    val layerId: String? = null,
)

data class Choice(
    val id: String,
    val label: String,
    val action: Action,
)

/** Physical controls that represent a sequence of discrete steps, not one idempotent command. */
internal fun Action.allowsQueuedRepeat(): Boolean = type in setOf(
    "navigate",
    "mode_cycle",
    "access_cycle",
    "reasoning_depth",
)

internal val FallbackInputs = listOf(
    Input("key_accept", Input.Kind.KEY, "Accept", "check"),
    Input("key_reject", Input.Kind.KEY, "Reject", "close"),
    Input("key_voice", Input.Kind.KEY, "Voice", "mic"),
    Input("key_new_task", Input.Kind.KEY, "New task", "add"),
    Input("key_stop", Input.Kind.KEY, "Stop", "stop"),
    Input("key_mode", Input.Kind.KEY, "Mode", "cycle"),
    Input("key_clear", Input.Kind.KEY, "Delete", "clear"),
    Input("key_focus", Input.Kind.KEY, "Next agent", "agent"),
    Input("key_up", Input.Kind.KEY, "Up", "up"),
    Input("key_down", Input.Kind.KEY, "Down", "down"),
    Input("key_left", Input.Kind.KEY, "Left", "left"),
    Input("key_right", Input.Kind.KEY, "Right", "right"),
    Input("key_attach", Input.Kind.KEY, "Focus Codex", "focus"),
)
