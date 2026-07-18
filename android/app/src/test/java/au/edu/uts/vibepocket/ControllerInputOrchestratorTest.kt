package au.edu.uts.vibepocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerInputOrchestratorTest {
    @Test
    fun frontmostVerifiedActionUsesHidWithoutBridgeTraffic() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = ControllerInputOrchestrator(hid, bridge)
        val action = ControllerAction("approve")

        assertTrue(orchestrator.activate(snapshot(action), INPUT_ID, ControllerGesture.TAP))
        assertEquals(listOf(action), hid.sent)
        assertEquals(emptyList<BridgeCall>(), bridge.calls)
    }

    @Test
    fun disabledCapabilityCannotBypassThePlannerThroughHid() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = ControllerInputOrchestrator(hid, bridge)

        assertFalse(
            orchestrator.activate(
                snapshot(ControllerAction("approve"), controls = allControls.copy(approve = false)),
                INPUT_ID,
                ControllerGesture.TAP,
            ),
        )
        assertTrue(hid.sent.isEmpty())
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun structuredCodexInputKeepsNavigationOnTheBridge() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = ControllerInputOrchestrator(hid, bridge)
        val question = CodexQuestion(
            questionIndex = 0,
            questionCount = 1,
            header = "Scope",
            question = "Continue?",
            options = listOf(CodexQuestionOption("Yes", "")),
            selectedOptionIndex = 0,
            hasSpokenAnswer = false,
            isSecret = false,
        )

        assertTrue(
            orchestrator.activate(
                snapshot(ControllerAction("navigate", direction = "down"), userInput = question),
                INPUT_ID,
                ControllerGesture.TAP,
            ),
        )
        assertTrue(hid.sent.isEmpty())
        assertEquals(listOf(BridgeCall.Activate(INPUT_ID, ControllerGesture.TAP)), bridge.calls)
    }

    @Test
    fun failedHidDeliveryFallsBackToTheSameBridgeInvocation() {
        val hid = FakeHid(sendResult = false)
        val bridge = FakeBridge()
        val orchestrator = ControllerInputOrchestrator(hid, bridge)

        assertTrue(
            orchestrator.activate(
                snapshot(ControllerAction("mode_cycle")),
                INPUT_ID,
                ControllerGesture.TAP,
            ),
        )
        assertEquals(1, hid.sent.size)
        assertEquals(listOf(BridgeCall.Activate(INPUT_ID, ControllerGesture.TAP)), bridge.calls)
    }

    @Test
    fun deliveredReasoningStepPublishesAnImmediateLocalAction() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val delivered = mutableListOf<ControllerAction>()
        val orchestrator = ControllerInputOrchestrator(hid, bridge, delivered::add)
        val action = ControllerAction("reasoning_depth", delta = 1)

        assertTrue(orchestrator.activate(snapshot(action), INPUT_ID, ControllerGesture.TAP))

        assertEquals(listOf(action), delivered)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun modelPickerUsesTheNativeHidShortcutWithoutBridgeFallback() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = ControllerInputOrchestrator(hid, bridge)

        assertTrue(orchestrator.openModelPicker(snapshot(ControllerAction("approve"))))

        assertEquals(listOf(ControllerAction("model_picker")), hid.sent)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun minimumReasoningDisablesOnlyTheDownwardPlan() {
        val minimum = ReasoningStatus(
            available = true,
            label = "5.6 Sol 最小",
            level = ReasoningLevel.MINIMAL,
            canIncrease = true,
            canDecrease = false,
        )
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = ControllerInputOrchestrator(hid, bridge)

        assertFalse(
            orchestrator.activate(
                snapshot(ControllerAction("reasoning_depth", delta = -1), reasoning = minimum),
                INPUT_ID,
                ControllerGesture.TAP,
            ),
        )
        assertTrue(
            orchestrator.activate(
                snapshot(ControllerAction("reasoning_depth", delta = 1), reasoning = minimum),
                INPUT_ID,
                ControllerGesture.TAP,
            ),
        )
        assertEquals(listOf(ControllerAction("reasoning_depth", delta = 1)), hid.sent)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun voiceHoldHasOneOwnerAndAlwaysReleasesTheSameHidChord() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = ControllerInputOrchestrator(hid, bridge)
        val voice = ControllerAction("voice")
        val snapshot = snapshot(voice)

        assertTrue(orchestrator.startVoice(snapshot, INPUT_ID))
        assertFalse(orchestrator.startVoice(snapshot, INPUT_ID))
        assertTrue(orchestrator.stopVoice(INPUT_ID))
        assertEquals(listOf(voice), hid.held)
        assertEquals(listOf(voice), hid.released)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun backgroundVoiceUsesBridgeWithoutSynthesizingAKey() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = ControllerInputOrchestrator(hid, bridge)

        assertTrue(orchestrator.startVoice(snapshot(ControllerAction("voice"), foreground = false), INPUT_ID))
        assertTrue(orchestrator.stopVoice(INPUT_ID))
        assertTrue(hid.held.isEmpty())
        assertEquals(listOf(BridgeCall.VoiceStart(INPUT_ID), BridgeCall.VoiceStop(INPUT_ID)), bridge.calls)
    }

    private class FakeHid(
        private val sendResult: Boolean = true,
        private val holdResult: Boolean = true,
    ) : ControllerHidTransport {
        val sent = mutableListOf<ControllerAction>()
        val held = mutableListOf<ControllerAction>()
        val released = mutableListOf<ControllerAction>()
        var releaseAllCount = 0

        override fun send(action: ControllerAction): Boolean {
            sent += action
            return sendResult
        }

        override fun pressAndHold(action: ControllerAction): Boolean {
            held += action
            return holdResult
        }

        override fun releaseHeld(action: ControllerAction): Boolean {
            released += action
            return true
        }

        override fun releaseAnyHeld(): Boolean {
            releaseAllCount += 1
            return true
        }

        override fun startNavigationRepeat(action: ControllerAction): Boolean = send(action)

        override fun stopNavigationRepeat() = Unit
    }

    private sealed interface BridgeCall {
        data class Activate(val inputId: String, val gesture: ControllerGesture) : BridgeCall
        data class VoiceStart(val inputId: String) : BridgeCall
        data class VoiceStop(val inputId: String) : BridgeCall
    }

    private class FakeBridge : ControllerBridgeTransport {
        val calls = mutableListOf<BridgeCall>()

        override fun activate(inputId: String, gesture: ControllerGesture): Boolean {
            calls += BridgeCall.Activate(inputId, gesture)
            return true
        }

        override fun startVoice(inputId: String): Boolean {
            calls += BridgeCall.VoiceStart(inputId)
            return true
        }

        override fun stopVoice(inputId: String): Boolean {
            calls += BridgeCall.VoiceStop(inputId)
            return true
        }
    }

    private fun snapshot(
        action: ControllerAction,
        controls: DesktopControls = allControls,
        foreground: Boolean = true,
        reasoning: ReasoningStatus = ReasoningStatus(
            available = true,
            label = "High",
            level = ReasoningLevel.HIGH,
            canIncrease = true,
            canDecrease = true,
        ),
        userInput: CodexQuestion? = null,
    ): PocketSnapshot = PocketSnapshot(
        revision = "r_test",
        status = BridgeStatus("ready", null),
        controls = controls,
        controller = ControllerState(
            profile = ControllerProfile(
                version = 3,
                inputs = listOf(ControllerInput(INPUT_ID, InputKind.KEY, "Test", "test")),
                workflows = emptyList(),
                layers = listOf(
                    ControllerLayer(
                        id = "layer-1",
                        name = "Default",
                        color = "#FFFFFF",
                        bindings = mapOf(
                            INPUT_ID to BindingDescriptor(mapOf(ControllerGesture.TAP to action)),
                        ),
                    ),
                ),
            ),
            gestures = emptyList(),
            actionCatalog = emptyList(),
            activeLayerId = "layer-1",
            desktopFocused = foreground,
            taskState = TaskState.IDLE,
            agents = emptyList(),
            focusedAgentIndex = -1,
            focusedAgentId = null,
            voice = VoiceStatus(available = true, active = false),
            mode = SelectorStatus(available = true, label = "Default"),
            reasoning = reasoning,
            userInput = userInput,
        ),
    )

    private companion object {
        const val INPUT_ID = "key_test"
        val allControls = DesktopControls(
            voice = true,
            stop = true,
            newTask = true,
            approve = true,
            reject = true,
            clearInput = true,
            focusAgent = true,
            modeCycle = true,
            accessCycle = true,
            navigate = true,
            reasoning = true,
            workflow = true,
        )
    }
}
