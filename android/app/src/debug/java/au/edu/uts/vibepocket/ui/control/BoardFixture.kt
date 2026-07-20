package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Agent
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Desktop
import au.edu.uts.vibepocket.control.Model
import au.edu.uts.vibepocket.control.Question
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Status
import au.edu.uts.vibepocket.control.Voice
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Binding
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.profile.Layer
import au.edu.uts.vibepocket.profile.Profile
import au.edu.uts.vibepocket.profile.Workflow
import au.edu.uts.vibepocket.ui.Theme
import au.edu.uts.vibepocket.ui.preference.Hand
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun BoardPreview(
    snapshot: Snapshot,
    inFlightIds: Set<String> = emptySet(),
    dark: Boolean = false,
    landscape: Boolean = false,
) {
    Theme(dark = dark) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(Modifier.fillMaxSize()) {
                if (!landscape) {
                    Row(
                        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Vibe Pocket", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        IconButton(
                            onClick = {},
                            modifier = Modifier.semantics { contentDescription = "Open settings" },
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    Screen(
                        snapshot = snapshot,
                        hidNavigationAvailable = true,
                        inFlightIds = inFlightIds,
                        contextTransitionPending = false,
                        onInput = { _, _ -> },
                        onNavigationRepeat = { _, _ -> },
                        onVoiceStart = { false },
                        onVoiceStop = {},
                        onAgent = {},
                        onModel = { false },
                        onMode = { false },
                        onReasoning = { false },
                        onLayer = { false },
                        voiceInput = dedicatedVoiceInput(snapshot),
                        onSettings = {},
                        hand = Hand.RIGHT,
                    )
                }
                if (!landscape) {
                    Voice(
                        input = dedicatedVoiceInput(snapshot),
                        snapshot = snapshot,
                        inFlightIds = inFlightIds,
                        onInput = { _, _ -> },
                        onVoiceStart = { false },
                        onVoiceStop = {},
                        blocked = !snapshot.transportFresh,
                        modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}

internal object Fixtures {
    private val inputs = listOf(
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
        Input("joystick_up", Input.Kind.JOYSTICK, "Review", "review"),
        Input("joystick_down", Input.Kind.JOYSTICK, "Debug", "debug"),
        Input("joystick_left", Input.Kind.JOYSTICK, "Refactor", "refactor"),
        Input("joystick_right", Input.Kind.JOYSTICK, "Tests", "test"),
    )
    private val workflows = listOf(
        Workflow("review-pr", "Review PR", "Review the current change."),
        Workflow("debug", "Debug", "Find and fix the current failure."),
        Workflow("refactor", "Refactor", "Improve the current code."),
        Workflow("test", "Tests", "Run the relevant tests."),
    )
    private val bindings = mapOf(
        "key_accept" to tap(Action("approve")),
        "key_reject" to tap(Action("reject")),
        "key_voice" to tap(Action("voice")),
        "key_new_task" to tap(Action("new_task")),
        "key_stop" to tap(Action("stop")),
        "key_mode" to tap(Action("mode_cycle")),
        "key_clear" to Binding(
            mapOf(
                Gesture.Kind.TAP to Action("delete_backward"),
                Gesture.Kind.HOLD to Action("clear_input"),
            ),
        ),
        "key_focus" to tap(Action("focus_next")),
        "key_up" to tap(Action("navigate", direction = "up")),
        "key_down" to tap(Action("navigate", direction = "down")),
        "key_left" to tap(Action("navigate", direction = "left")),
        "key_right" to tap(Action("navigate", direction = "right")),
        "key_attach" to tap(Action("attach")),
        "joystick_up" to tap(Action("workflow", workflowId = "review-pr")),
        "joystick_down" to tap(Action("workflow", workflowId = "debug")),
        "joystick_left" to tap(Action("workflow", workflowId = "refactor")),
        "joystick_right" to tap(Action("workflow", workflowId = "test")),
    )
    private val layers = (1..6).map { index ->
        Layer("layer-$index", if (index == 1) "Default" else "Layer $index", null, bindings)
    }
    private val profile = Profile(4, inputs, workflows, layers)
    private val capabilities = Capabilities(
        voice = true,
        stop = true,
        newTask = true,
        approve = true,
        reject = true,
        clearInput = true,
        focusAgent = true,
        modeCycle = true,
        modelPicker = true,
        model = true,
        accessCycle = true,
        navigate = true,
        reasoning = true,
        workflow = true,
    )

    fun snapshot(
        activity: Activity = Activity.IDLE,
        message: String? = null,
        question: Question? = null,
        voiceActive: Boolean = false,
        agentCount: Int = 8,
        focusNext: Boolean = true,
    ): Snapshot = Snapshot(
        revision = "preview-${activity.wireValue}",
        status = Status("ready", message),
        capabilities = capabilities,
        desktop = Desktop(
            profile = if (focusNext) {
                profile
            } else {
                profile.copy(
                    layers = profile.layers.map { layer ->
                        layer.copy(bindings = layer.bindings - "key_focus")
                    },
                )
            },
            gestures = Gesture.Kind.entries.map { Gesture(it, it.wireValue.replace('_', ' ')) },
            choices = emptyList(),
            activeLayerId = "layer-1",
            foreground = true,
            activity = activity,
            agents = (1..agentCount.coerceIn(0, 24)).map { index ->
                Agent(
                    id = "agent-${index.toString(16).padStart(24, '0')}",
                    label = if (index == 1) "Vibe Pocket UI" else "Recent task $index",
                    activity = if (index == 2) Activity.WAITING else Activity.IDLE,
                    focused = index == 1,
                )
            },
            focusedAgentIndex = 0,
            focusedAgentId = "agent-${"1".padStart(24, '0')}",
            voice = Voice(available = true, active = voiceActive),
            mode = Selector(
                available = true,
                label = "Default",
                id = "default",
                options = listOf(
                    Selector.Option("default", "Default", true),
                    Selector.Option("plan", "Plan", false),
                ),
            ),
            model = Model(
                available = true,
                id = "gpt-5.4",
                label = "GPT-5.4",
                options = listOf(Model.Option("gpt-5.4", "GPT-5.4", true)),
            ),
            reasoning = Reasoning(
                available = true,
                label = "Medium",
                level = Reasoning.Level.MEDIUM,
                canIncrease = true,
                canDecrease = true,
                increaseTo = Reasoning.Level.HIGH,
                decreaseTo = Reasoning.Level.LOW,
                options = Reasoning.Level.entries,
            ),
            question = question,
        ),
    )

    private fun tap(action: Action) = Binding(mapOf(Gesture.Kind.TAP to action))
}
