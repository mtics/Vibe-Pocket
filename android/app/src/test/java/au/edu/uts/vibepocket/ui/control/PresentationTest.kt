package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Agent
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Desktop
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Status
import au.edu.uts.vibepocket.control.Tasks
import au.edu.uts.vibepocket.control.Voice
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Binding
import au.edu.uts.vibepocket.profile.Choice
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.profile.Layer
import au.edu.uts.vibepocket.profile.Profile
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationTest {
    @Test
    fun inputLabelsFollowRemappedActionRatherThanPhysicalInput() {
        val input = Input("joystick_up", Input.Kind.JOYSTICK, "Up", "up")
        val workflow = Action(type = "workflow", workflowId = "debug")
        val choices = listOf(Choice("debug", "Debug", workflow))

        assertEquals("Debug", inputLabel(workflow, input, choices))
        assertEquals("Accept", inputLabel(Action("approve"), input, choices))
        assertEquals("Up", inputLabel(null, input, choices))
    }

    @Test
    fun reasoningStepsFollowDeltaAcrossRemappedInputs() {
        val clockwise = Input("dial_cw", Input.Kind.DIAL, "Clockwise", "add")
        val counterClockwise = Input("dial_ccw", Input.Kind.DIAL, "Counter-clockwise", "remove")
        val joystick = Input("joystick_left", Input.Kind.JOYSTICK, "Left", "left")
        val snapshot = snapshot(
            inputs = listOf(clockwise, counterClockwise, joystick),
            actions = mapOf(
                clockwise.id to Action("workflow", workflowId = "review-pr"),
                counterClockwise.id to Action("reasoning_depth", delta = 1),
                joystick.id to Action("reasoning_depth", delta = -1),
            ),
        )

        assertEquals(joystick, reasoningInput(listOf(clockwise, counterClockwise, joystick), snapshot, -1))
        assertEquals(counterClockwise, reasoningInput(listOf(clockwise, counterClockwise, joystick), snapshot, 1))
        assertNull(reasoningInput(listOf(clockwise), snapshot, -1))
    }

    @Test
    fun voiceAccessibilityActionTogglesWithListeningState() {
        assertEquals("Start listening", voiceAccessibilityAction(active = false))
        assertEquals("Stop listening", voiceAccessibilityAction(active = true))
    }

    @Test
    fun voiceMappingIdentitySurvivesAvailabilityAndActiveRefreshes() {
        val input = Input("touch_primary", Input.Kind.TOUCH, "Touch", "touch")
        val idle = snapshot(
            inputs = listOf(input),
            actions = mapOf(input.id to Action("voice")),
            voice = Voice(available = true, active = false),
            capabilities = Capabilities(voice = true),
        )
        val active = snapshot(
            inputs = listOf(input),
            actions = mapOf(input.id to Action("voice")),
            voice = Voice(available = false, active = true),
            capabilities = Capabilities(voice = true),
        )

        assertEquals(idle.voiceMappingIdentity(input.id), active.voiceMappingIdentity(input.id))
        assertEquals(input, dedicatedVoiceInput(active))
    }

    @Test
    fun dedicatedVoiceControlPrefersTheInputThatStartedListening() {
        val owner = Input("dial_voice", Input.Kind.DIAL, "Dial", "cycle")
        val newlyMapped = Input("touch_voice", Input.Kind.TOUCH, "Touch", "touch")
        val snapshot = snapshot(
            inputs = listOf(owner, newlyMapped),
            actions = mapOf(newlyMapped.id to Action("voice")),
            voice = Voice(available = false, active = true),
            capabilities = Capabilities(voice = true),
        )

        assertEquals(owner, dedicatedVoiceInput(snapshot, activeOwnerInputId = owner.id))
    }

    @Test
    fun dedicatedVoiceSlotRemainsPresentWhenTheActiveLayerHasNoVoiceMapping() {
        val snapshot = snapshot(
            inputs = emptyList(),
            actions = emptyMap(),
            voice = Voice(available = false, active = false),
            capabilities = Capabilities(voice = false),
        )

        assertEquals("key_voice", dedicatedVoiceInput(snapshot).id)
    }

    @Test
    fun reasoningStepsNameTheExactTargetForTalkBack() {
        assertEquals(
            "Increase reasoning to High",
            reasoningStepDescription("Increase reasoning", Reasoning.Level.HIGH),
        )
        assertEquals(
            "Decrease reasoning unavailable",
            reasoningStepDescription("Decrease reasoning", null),
        )
    }

    @Test
    fun reasoningPendingStateKeepsConfirmedAndTargetValuesDistinct() {
        val state = Reasoning(
            available = true,
            label = "Medium",
            level = Reasoning.Level.MEDIUM,
            canIncrease = true,
            canDecrease = true,
        )

        assertEquals(
            Reasoning.Level.HIGH,
            reasoningPendingTarget(setOf("input:key_accept:tap", "reasoning:high")),
        )
        assertEquals("Medium -> High", reasoningDisplay(state, Reasoning.Level.HIGH))
        assertEquals(
            "Reasoning, Medium, changing to High",
            reasoningDescription(state, Reasoning.Level.HIGH),
        )
        assertEquals("Medium", reasoningDisplay(state, null))
    }

    @Test
    fun contextTransitionPendingIsStructuredSessionState() {
        assertTrue(au.edu.uts.vibepocket.session.State(contextTransitionPending = true).contextTransitionPending)
        assertFalse(
            au.edu.uts.vibepocket.session.State(
                inFlightIds = setOf("agent:a", "layer:default", "model:o3"),
            ).contextTransitionPending,
        )
    }

    @Test
    fun talkBackLabelsExposeEveryNonTapGesture() {
        assertNull(gestureAccessibilityAction(Gesture.Kind.TAP))
        assertEquals("Run double-tap mapping", gestureAccessibilityAction(Gesture.Kind.DOUBLE_TAP))
        assertEquals("Run hold mapping", gestureAccessibilityAction(Gesture.Kind.HOLD))
    }

    @Test
    fun unrepresentedInputsPreserveEveryConfigurableControl() {
        val visible = Input("key_accept", Input.Kind.KEY, "Accept", "check")
        val touch = Input("touch_primary", Input.Kind.TOUCH, "Touch", "touch")
        val dial = Input("dial_primary", Input.Kind.DIAL, "Dial", "cycle")

        assertEquals(listOf(touch, dial), unrepresentedInputs(listOf(visible, touch, dial), setOf(visible.id)))
    }

    @Test
    fun agentRailShowsOnlyCompleteTargetsAtRest() {
        assertEquals(145.dp, agentChipWidth(298.dp))
        assertEquals(175.dp, agentChipWidth(358.dp))
        assertEquals(240.dp, agentChipWidth(240.dp))
        assertEquals(298.dp, agentChipWidth(298.dp, largeText = true))
    }

    @Test
    fun taskCatalogLabelsDistinguishEmptyFromUnavailable() {
        val stale = Agent(
            id = "agent-aaaaaaaaaaaaaaaaaaaaaaaa",
            label = "Last task",
            activity = Activity.IDLE,
            focused = false,
            freshness = Agent.Freshness.STALE,
            actionable = false,
        )

        assertEquals("Last known", agentStatusLabel(stale))
        assertEquals("No active Codex tasks", emptyTasksLabel(Tasks.Fresh))
        assertEquals(
            "Last known tasks are unavailable",
            emptyTasksLabel(Tasks(Tasks.Availability.STALE, null)),
        )
        assertEquals("Codex task list unavailable", emptyTasksLabel(Tasks.Unavailable))
    }

    @Test
    fun largeTextStartsAtTheDedicatedAccessibilityGeometry() {
        assertFalse(largeText(1.3f))
        assertTrue(largeText(1.5f))
        assertTrue(largeText(2f))
    }

    @Test
    fun agentPositionNamesFocusAndCollectionPosition() {
        assertEquals("Focused, 1 of 24", agentPositionDescription(focused = true, index = 0, total = 24))
        assertEquals("8 of 24", agentPositionDescription(focused = false, index = 7, total = 24))
    }

    private fun snapshot(
        inputs: List<Input>,
        actions: Map<String, Action>,
        voice: Voice? = null,
        capabilities: Capabilities = Capabilities(reasoning = true, workflow = true),
    ): Snapshot {
        val layer = Layer(
            id = "layer-1",
            name = "Default",
            color = "#F4F4F2",
            bindings = actions.mapValues { (_, action) ->
                Binding(mapOf(Gesture.Kind.TAP to action))
            },
        )
        return Snapshot(
            revision = "r1",
            status = Status("ready", null),
            capabilities = capabilities,
            desktop = Desktop(
                profile = Profile(1, inputs, emptyList(), listOf(layer)),
                gestures = emptyList(),
                choices = emptyList(),
                activeLayerId = layer.id,
                foreground = true,
                activity = Activity.IDLE,
                agents = emptyList(),
                focusedAgentIndex = -1,
                focusedAgentId = null,
                voice = voice,
                mode = Selector(false, ""),
                reasoning = Reasoning(
                    available = true,
                    label = "Medium",
                    level = Reasoning.Level.MEDIUM,
                    canIncrease = true,
                    canDecrease = true,
                ),
            ),
        )
    }
}
