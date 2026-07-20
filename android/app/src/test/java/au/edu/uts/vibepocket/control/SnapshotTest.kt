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
    fun reasoningRequiresForegroundDesktopAndAdvertisedCapability() {
        val action = Action("reasoning_depth", delta = 1)

        assertTrue(snapshot(action).inputEnabled(InputId))
        assertFalse(snapshot(action, foreground = false).inputEnabled(InputId))
        assertFalse(snapshot(action, activity = Activity.EXECUTING).inputEnabled(InputId))
        assertFalse(snapshot(action, activity = Activity.THINKING).inputEnabled(InputId))
        assertFalse(
            snapshot(
                action,
                capabilities = Capabilities(reasoning = false),
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
                mode = Selector(false, ""),
                reasoning = Reasoning(
                    available = true,
                    label = "Medium",
                    level = Reasoning.Level.MEDIUM,
                    canIncrease = true,
                    canDecrease = true,
                ),
                question = question,
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
    }
}
