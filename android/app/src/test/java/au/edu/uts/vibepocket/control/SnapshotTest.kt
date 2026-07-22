package au.edu.uts.vibepocket.control

import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Binding
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.profile.Layer
import au.edu.uts.vibepocket.profile.Profile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTest {
    @Test
    fun reasoningRequiresConfirmedTaskAndAdvertisedCapabilityButNotForegroundOrIdleState() {
        val action = Action("reasoning_depth", delta = 1)

        assertTrue(snapshot(action).inputEnabled(InputId))
        assertTrue(snapshot(action, foreground = false).inputEnabled(InputId))
        assertTrue(snapshot(action, activity = Activity.EXECUTING).inputEnabled(InputId))
        assertTrue(snapshot(action, activity = Activity.THINKING).inputEnabled(InputId))
        assertFalse(
            snapshot(
                action,
                binding = Desktop.Binding.Unbound,
            ).inputEnabled(InputId),
        )
        assertFalse(
            snapshot(
                action,
                capabilities = Capabilities(reasoning = false),
            ).inputEnabled(InputId),
        )
    }

    @Test
    fun nativeSettingsSelectionsRemainAvailableInTheBackground() {
        val snapshot = snapshot(
            action = Action("mode_cycle"),
            capabilities = Capabilities(model = true, modeCycle = true, reasoning = true),
            foreground = false,
        )

        assertTrue(snapshot.modelSelectionEnabled("model-2"))
        assertFalse(snapshot.modeSelectionEnabled("plan"))
        assertTrue(snapshot.reasoningSelectionEnabled(Reasoning.Level.HIGH))
    }

    @Test
    fun settingsIgnoreDesktopLockButRequireFreshTransportAndAppServer() {
        val locked = Desktop.Binding(
            Desktop.Binding.State.CONFLICT,
            "agent-222222222222222222222222",
            Desktop.Binding.Target.bound(Target),
        )

        val capabilities = Capabilities(model = true, reasoning = true)
        assertFalse(snapshot(Action("approve"), capabilities, binding = locked, transportFresh = false)
            .modelSelectionEnabled("model-2"))
        assertFalse(snapshot(Action("approve"), capabilities, binding = locked, transportFresh = false)
            .reasoningSelectionEnabled(Reasoning.Level.HIGH))
        assertFalse(snapshot(Action("approve"), capabilities, binding = locked, appServerFresh = false)
            .modelSelectionEnabled("model-2"))
    }

    @Test
    fun explicitVisibleInputActionsRemainAvailableInTheBackground() {
        assertTrue(
            snapshot(
                action = Action("navigate", direction = "up"),
                capabilities = Capabilities(navigate = true),
                foreground = false,
            ).inputEnabled(InputId),
        )
        assertTrue(
            snapshot(
                action = Action("workflow", workflowId = "review-pr"),
                capabilities = Capabilities(workflow = true),
                foreground = false,
            ).inputEnabled(InputId),
        )
    }

    @Test
    fun questionDisablesProfileConfiguredModelPicker() {
        val action = Action("model_picker")
        val capabilities = Capabilities(modelPicker = true)

        assertTrue(snapshot(action, capabilities = capabilities).inputEnabled(InputId))
        assertFalse(
            snapshot(
                action,
                capabilities = capabilities,
                question = question(),
            ).inputEnabled(InputId),
        )
    }

    @Test
    fun approvalIsDisabledWhileTheTaskIsRunning() {
        val action = Action("approve")
        val capabilities = Capabilities(approve = true)

        assertTrue(snapshot(action, capabilities = capabilities).inputEnabled(InputId))
        assertFalse(
            snapshot(
                action,
                capabilities = capabilities,
                activity = Activity.THINKING,
            ).inputEnabled(InputId),
        )
        assertFalse(
            snapshot(
                action,
                capabilities = capabilities,
                activity = Activity.EXECUTING,
            ).inputEnabled(InputId),
        )
    }

    private fun snapshot(
        action: Action,
        capabilities: Capabilities = Capabilities(reasoning = true),
        foreground: Boolean = true,
        activity: Activity = Activity.IDLE,
        question: Question? = null,
        transportFresh: Boolean = true,
        appServerFresh: Boolean = true,
        binding: Desktop.Binding = Desktop.Binding(
            Desktop.Binding.State.CONFIRMED,
            "agent-111111111111111111111111",
            Desktop.Binding.Target.bound(Target),
        ),
    ): Snapshot {
        val input = Input(InputId, Input.Kind.KEY, "Mapped action", "action")
        val layer = Layer(
            id = "layer-1",
            name = "Default",
            color = null,
            bindings = mapOf(
                input.id to Binding(mapOf(Gesture.Kind.TAP to action)),
            ),
        )
        return Snapshot(
            revision = "r1",
            status = Status("ready", null),
            capabilities = capabilities,
            desktop = Desktop(
                profile = Profile(1, listOf(input), emptyList(), listOf(layer)),
                gestures = emptyList(),
                choices = emptyList(),
                activeLayerId = layer.id,
                foreground = foreground,
                activity = activity,
                agents = emptyList(),
                focusedAgentIndex = -1,
                focusedAgentId = null,
                voice = null,
                mode = Selector(
                    available = true,
                    label = "Default",
                    id = "default",
                    options = listOf(Selector.Option("plan", "Plan", false)),
                ),
                model = Model(
                    available = true,
                    id = "model-1",
                    label = "Model 1",
                    options = listOf(Model.Option("model-2", "Model 2", false)),
                ),
                reasoning = Reasoning(
                    available = true,
                    label = "Medium",
                    level = Reasoning.Level.MEDIUM,
                    canIncrease = true,
                    canDecrease = true,
                    options = listOf(Reasoning.Level.LOW, Reasoning.Level.MEDIUM, Reasoning.Level.HIGH),
                ),
                question = question,
                binding = binding,
            ),
            transportFresh = transportFresh,
            sources = Sources(
                appServer = Sources.Source(appServerFresh),
                desktopUI = Sources.Source(true),
            ),
        )
    }

    private fun question() = Question(
        index = 0,
        count = 1,
        header = "Scope",
        text = "Continue?",
        options = listOf(Question.Option("Yes", "")),
        selectedOptionIndex = 0,
        hasSpokenAnswer = false,
        isSecret = false,
    )

    private companion object {
        const val InputId = "key_mapped"
        val Target = TargetRef(
            "thread-1",
            "agent-111111111111111111111111",
            4,
            "bridge-1",
            7,
            "workspace-1",
        )
    }
}
