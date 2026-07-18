package au.edu.uts.vibepocket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HidNavigationRepeatTest {
    @Test
    fun repeatsOnlyAnUnambiguousNavigationTapBinding() {
        assertTrue(snapshot().supportsHidNavigationRepeat("key_up", hidConnected = true))
        assertFalse(snapshot().supportsHidNavigationRepeat("key_up", hidConnected = false))
        assertFalse(snapshot(holdAction = ControllerAction("workflow", workflowId = "debug"))
            .supportsHidNavigationRepeat("key_up", hidConnected = true))
    }

    @Test
    fun neverRepeatsWhileCodexOwnsStructuredNavigation() {
        assertFalse(snapshot(userInput = CodexQuestion(
            questionIndex = 0,
            questionCount = 1,
            header = "Question",
            question = "Continue?",
            options = listOf(CodexQuestionOption("Yes", "")),
            selectedOptionIndex = 0,
            hasSpokenAnswer = false,
            isSecret = false,
        )).supportsHidNavigationRepeat("key_up", hidConnected = true))
    }

    private fun snapshot(
        holdAction: ControllerAction? = null,
        userInput: CodexQuestion? = null,
    ): PocketSnapshot {
        val gestures = buildMap {
            put(ControllerGesture.TAP, ControllerAction("navigate", direction = "up"))
            holdAction?.let { put(ControllerGesture.HOLD, it) }
        }
        return PocketSnapshot(
            revision = "r_test",
            status = BridgeStatus("ready", null),
            controls = DesktopControls(navigate = true),
            controller = ControllerState(
                profile = ControllerProfile(
                    version = 3,
                    inputs = listOf(ControllerInput("key_up", InputKind.KEY, "Up", "up")),
                    workflows = emptyList(),
                    layers = listOf(ControllerLayer("layer-1", "Default", "#FFFFFF", mapOf(
                        "key_up" to BindingDescriptor(gestures),
                    ))),
                ),
                gestures = emptyList(),
                actionCatalog = emptyList(),
                activeLayerId = "layer-1",
                taskState = TaskState.IDLE,
                agents = emptyList(),
                focusedAgentIndex = -1,
                focusedAgentId = null,
                voice = null,
                mode = SelectorStatus(false, ""),
                reasoning = SelectorStatus(false, ""),
                userInput = userInput,
            ),
        )
    }
}
