package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Desktop
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Status
import au.edu.uts.vibepocket.control.Voice
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Binding
import au.edu.uts.vibepocket.profile.Choice
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.profile.Layer
import au.edu.uts.vibepocket.profile.Profile
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
    fun contextTransitionsBlockForAgentLayerAndModelOnly() {
        assertTrue(contextTransitionPending(setOf("agent:a")))
        assertTrue(contextTransitionPending(setOf("layer:default")))
        assertTrue(contextTransitionPending(setOf("model:o3")))
        assertFalse(contextTransitionPending(setOf("input:key_accept:tap", "mapping:key_accept:tap")))
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
    fun agentChipWidthDoesNotDependOnFocus() {
        assertEquals(160, AgentChipWidthDp)
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
