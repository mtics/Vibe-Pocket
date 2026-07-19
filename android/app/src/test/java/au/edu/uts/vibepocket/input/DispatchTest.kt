package au.edu.uts.vibepocket.input

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Desktop
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatchTest {
    @Test
    fun frontmostVerifiedActionUsesHidWithoutBridgeTraffic() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)
        val action = Action("approve")

        assertTrue(orchestrator.activate(snapshot(action), INPUT_ID, Gesture.Kind.TAP))
        assertEquals(listOf(action), hid.sent)
        assertEquals(emptyList<BridgeCall>(), bridge.calls)
    }

    @Test
    fun disabledCapabilityCannotBypassThePlannerThroughHid() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)

        assertFalse(
            orchestrator.activate(
                snapshot(Action("approve"), capabilities = allCapabilities.copy(approve = false)),
                INPUT_ID,
                Gesture.Kind.TAP,
            ),
        )
        assertTrue(hid.sent.isEmpty())
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun structuredCodexInputKeepsNavigationOnTheBridge() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)
        val question = Question(
            index = 0,
            count = 1,
            header = "Scope",
            text = "Continue?",
            options = listOf(Question.Option("Yes", "")),
            selectedOptionIndex = 0,
            hasSpokenAnswer = false,
            isSecret = false,
        )

        assertTrue(
            orchestrator.activate(
                snapshot(Action("navigate", direction = "down"), question = question),
                INPUT_ID,
                Gesture.Kind.TAP,
            ),
        )
        assertTrue(hid.sent.isEmpty())
        assertEquals(listOf(BridgeCall.Activate(INPUT_ID, Gesture.Kind.TAP)), bridge.calls)
    }

    @Test
    fun failedHidDeliveryFallsBackToTheSameBridgeInvocation() {
        val hid = FakeHid(sendResult = false)
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)

        assertTrue(
            orchestrator.activate(
                snapshot(Action("mode_cycle")),
                INPUT_ID,
                Gesture.Kind.TAP,
            ),
        )
        assertEquals(1, hid.sent.size)
        assertEquals(listOf(BridgeCall.Activate(INPUT_ID, Gesture.Kind.TAP)), bridge.calls)
    }

    @Test
    fun backgroundModeUsesTheBridgeInsteadOfBeingDisabled() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)

        assertTrue(
            orchestrator.activate(
                snapshot(Action("mode_cycle"), foreground = false),
                INPUT_ID,
                Gesture.Kind.TAP,
            ),
        )

        assertTrue(hid.sent.isEmpty())
        assertEquals(listOf(BridgeCall.Activate(INPUT_ID, Gesture.Kind.TAP)), bridge.calls)
    }

    @Test
    fun reasoningUsesHidAndPredictsTheVisibleDesktopSetting() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val delivered = mutableListOf<Action>()
        val orchestrator = Dispatch(hid, bridge, delivered::add)
        val action = Action("reasoning_depth", delta = 1)

        assertTrue(orchestrator.activate(snapshot(action), INPUT_ID, Gesture.Kind.TAP))

        assertEquals(listOf(action), delivered)
        assertEquals(listOf(action), hid.sent)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun predictionWaitsForTheCompleteReportTransaction() {
        val hid = FakeHid(deferSend = true)
        val delivered = mutableListOf<Action>()
        val action = Action("reasoning_depth", delta = 1)
        val orchestrator = Dispatch(hid, FakeBridge(), delivered::add)

        assertTrue(orchestrator.activate(snapshot(action), INPUT_ID, Gesture.Kind.TAP))
        assertTrue(delivered.isEmpty())

        hid.completeSend(true)

        assertEquals(listOf(action), delivered)
    }

    @Test
    fun queuedTransportFailureFallsBackOnlyAfterCompletion() {
        val hid = FakeHid(deferSend = true)
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)

        assertTrue(orchestrator.activate(snapshot(Action("mode_cycle")), INPUT_ID, Gesture.Kind.TAP))
        assertTrue(bridge.calls.isEmpty())

        hid.completeSend(false)

        assertEquals(listOf(BridgeCall.Activate(INPUT_ID, Gesture.Kind.TAP)), bridge.calls)
    }

    @Test
    fun lifecycleReleaseInvalidatesLateCompletionAndStopsRepeat() {
        val hid = FakeHid(deferSend = true)
        val bridge = FakeBridge()
        val delivered = mutableListOf<Action>()
        val orchestrator = Dispatch(hid, bridge, delivered::add)

        assertTrue(orchestrator.activate(snapshot(Action("mode_cycle")), INPUT_ID, Gesture.Kind.TAP))
        orchestrator.release()
        hid.completeSend(false)

        assertTrue(delivered.isEmpty())
        assertTrue(bridge.calls.isEmpty())
        assertEquals(1, hid.stopRepeatCount)
        assertEquals(1, hid.releaseAllCount)
    }

    @Test
    fun modelPickerDoesNotDependOnReasoningAvailability() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)

        assertTrue(
            orchestrator.openModel(
                snapshot(Action("approve"), reasoning = Reasoning.Unavailable),
            ),
        )

        assertEquals(listOf(Action("model_picker")), hid.sent)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun modelPickerFallsBackToTheBridgeWhenHidIsUnavailable() {
        val hid = FakeHid(sendResult = false)
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)

        assertTrue(orchestrator.openModel(snapshot(Action("approve"))))

        assertEquals(listOf(Action("model_picker")), hid.sent)
        assertEquals(listOf(BridgeCall.OpenModel), bridge.calls)
    }

    @Test
    fun staleTransportNeverSendsTheModelPickerThroughHid() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)

        assertTrue(orchestrator.openModel(snapshot(Action("approve"), transportFresh = false)))

        assertTrue(hid.sent.isEmpty())
        assertEquals(listOf(BridgeCall.OpenModel), bridge.calls)
    }

    @Test
    fun backwardDeleteUsesOneNativeBackspaceAction() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)
        val action = Action("delete_backward")

        assertTrue(orchestrator.activate(snapshot(action), INPUT_ID, Gesture.Kind.TAP))

        assertEquals(listOf(action), hid.sent)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun minimumReasoningDisablesOnlyTheDownwardPlan() {
        val minimum = Reasoning(
            available = true,
            label = "5.6 Sol 最小",
            level = Reasoning.Level.MINIMAL,
            canIncrease = true,
            canDecrease = false,
        )
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)

        assertFalse(
            orchestrator.activate(
                snapshot(Action("reasoning_depth", delta = -1), reasoning = minimum),
                INPUT_ID,
                Gesture.Kind.TAP,
            ),
        )
        assertTrue(
            orchestrator.activate(
                snapshot(Action("reasoning_depth", delta = 1), reasoning = minimum),
                INPUT_ID,
                Gesture.Kind.TAP,
            ),
        )
        assertEquals(listOf(Action("reasoning_depth", delta = 1)), hid.sent)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun voiceHoldHasOneOwnerAndAlwaysReleasesTheSameChord() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)
        val voice = Action("voice")
        val snapshot = snapshot(voice)

        assertTrue(orchestrator.startVoice(snapshot, INPUT_ID))
        assertFalse(orchestrator.startVoice(snapshot, INPUT_ID))
        assertTrue(orchestrator.stopVoice(INPUT_ID))
        assertEquals(listOf(voice), hid.held)
        assertEquals(listOf(voice), hid.released)
        assertEquals(listOf(BridgeCall.VoiceStop(INPUT_ID)), bridge.calls)
    }

    @Test
    fun remappedStopReleasesTheRecordedHidOwnerAndSemanticFallback() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)
        val voice = Action("voice")

        assertTrue(orchestrator.startVoice(snapshot(voice), INPUT_ID))
        assertTrue(orchestrator.stopVoice("key_voice_on_another_layer"))

        assertEquals(listOf(voice), hid.released)
        assertEquals(listOf(BridgeCall.VoiceStop(INPUT_ID)), bridge.calls)
    }

    @Test
    fun backgroundVoiceUsesBridgeWithoutSynthesizingAKey() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)

        assertTrue(orchestrator.startVoice(snapshot(Action("voice"), foreground = false), INPUT_ID))
        assertTrue(orchestrator.stopVoice(INPUT_ID))
        assertTrue(hid.held.isEmpty())
        assertEquals(listOf(BridgeCall.VoiceStart(INPUT_ID), BridgeCall.VoiceStop(INPUT_ID)), bridge.calls)
    }

    private class FakeHid(
        private val sendResult: Boolean = true,
        private val deliveryResult: Boolean = true,
        private val deferSend: Boolean = false,
        private val holdResult: Boolean = true,
    ) : Hid {
        val sent = mutableListOf<Action>()
        val held = mutableListOf<Action>()
        val released = mutableListOf<Action>()
        var releaseAllCount = 0
        var stopRepeatCount = 0
        private val sendCompletions = ArrayDeque<(Boolean) -> Unit>()

        override fun send(action: Action, completion: (Boolean) -> Unit): Boolean {
            sent += action
            if (!sendResult) return false
            if (deferSend) sendCompletions += completion else completion(deliveryResult)
            return true
        }

        override fun press(action: Action): Boolean {
            held += action
            return holdResult
        }

        override fun release(action: Action): Boolean {
            released += action
            return true
        }

        override fun releaseAny(): Boolean {
            releaseAllCount += 1
            return true
        }

        override fun repeat(action: Action, completion: (Boolean) -> Unit): Boolean = send(action, completion)

        override fun stopRepeat() {
            stopRepeatCount += 1
        }

        fun completeSend(delivered: Boolean) {
            sendCompletions.removeFirst()(delivered)
        }
    }

    private sealed interface BridgeCall {
        data class Activate(val inputId: String, val gesture: Gesture.Kind) : BridgeCall
        data class VoiceStart(val inputId: String) : BridgeCall
        data class VoiceStop(val inputId: String) : BridgeCall
        data object OpenModel : BridgeCall
    }

    private class FakeBridge : Bridge {
        val calls = mutableListOf<BridgeCall>()

        override fun activate(inputId: String, gesture: Gesture.Kind): Boolean {
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

        override fun openModel(): Boolean {
            calls += BridgeCall.OpenModel
            return true
        }
    }

    private fun snapshot(
        action: Action,
        capabilities: Capabilities = allCapabilities,
        foreground: Boolean = true,
        reasoning: Reasoning = Reasoning(
            available = true,
            label = "High",
            level = Reasoning.Level.HIGH,
            canIncrease = true,
            canDecrease = true,
        ),
        question: Question? = null,
        transportFresh: Boolean = true,
    ): Snapshot = Snapshot(
        revision = "r_test",
        status = Status("ready", null),
        capabilities = capabilities,
        desktop = Desktop(
            profile = Profile(
                version = 3,
                inputs = listOf(Input(INPUT_ID, Input.Kind.KEY, "Test", "test")),
                workflows = emptyList(),
                layers = listOf(
                    Layer(
                        id = "layer-1",
                        name = "Default",
                        color = "#FFFFFF",
                        bindings = mapOf(
                            INPUT_ID to Binding(mapOf(Gesture.Kind.TAP to action)),
                        ),
                    ),
                ),
            ),
            gestures = emptyList(),
            choices = emptyList(),
            activeLayerId = "layer-1",
            foreground = foreground,
            activity = Activity.IDLE,
            agents = emptyList(),
            focusedAgentIndex = -1,
            focusedAgentId = null,
            voice = Voice(available = true, active = false),
            mode = Selector(available = true, label = "Default"),
            reasoning = reasoning,
            question = question,
        ),
        transportFresh = transportFresh,
    )

    private companion object {
        const val INPUT_ID = "key_test"
        val allCapabilities = Capabilities(
            voice = true,
            stop = true,
            newTask = true,
            approve = true,
            reject = true,
            clearInput = true,
            focusAgent = true,
            modeCycle = true,
            modelPicker = true,
            accessCycle = true,
            navigate = true,
            reasoning = true,
            workflow = true,
        )
    }
}
