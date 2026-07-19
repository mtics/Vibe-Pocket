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

class NavigationRepeatTest {
    @Test
    fun repeatsOnlyAnUnambiguousNavigationTapBinding() {
        assertTrue(snapshot().supportsHidNavigationRepeat("key_up", hidConnected = true))
        assertFalse(snapshot().supportsHidNavigationRepeat("key_up", hidConnected = false))
        assertFalse(snapshot(holdAction = Action("workflow", workflowId = "debug"))
            .supportsHidNavigationRepeat("key_up", hidConnected = true))
    }

    @Test
    fun neverRepeatsWhileCodexOwnsStructuredNavigation() {
        assertFalse(snapshot(question = Question(
            index = 0,
            count = 1,
            header = "Question",
            text = "Continue?",
            options = listOf(Question.Option("Yes", "")),
            selectedOptionIndex = 0,
            hasSpokenAnswer = false,
            isSecret = false,
        )).supportsHidNavigationRepeat("key_up", hidConnected = true))
    }

    private fun snapshot(
        holdAction: Action? = null,
        question: Question? = null,
    ): Snapshot {
        val gestures = buildMap {
            put(Gesture.Kind.TAP, Action("navigate", direction = "up"))
            holdAction?.let { put(Gesture.Kind.HOLD, it) }
        }
        return Snapshot(
            revision = "r_test",
            status = Status("ready", null),
            capabilities = Capabilities(navigate = true),
            desktop = Desktop(
                profile = Profile(
                    version = 3,
                    inputs = listOf(Input("key_up", Input.Kind.KEY, "Up", "up")),
                    workflows = emptyList(),
                    layers = listOf(Layer("layer-1", "Default", "#FFFFFF", mapOf(
                        "key_up" to Binding(gestures),
                    ))),
                ),
                gestures = emptyList(),
                choices = emptyList(),
                activeLayerId = "layer-1",
                foreground = true,
                activity = Activity.IDLE,
                agents = emptyList(),
                focusedAgentIndex = -1,
                focusedAgentId = null,
                voice = null,
                mode = Selector(false, ""),
                reasoning = Reasoning.Unavailable,
                question = question,
            ),
        )
    }
}
