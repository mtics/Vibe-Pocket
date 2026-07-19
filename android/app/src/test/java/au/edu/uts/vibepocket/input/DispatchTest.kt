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
    fun deliveredReasoningStepPublishesAnImmediateLocalAction() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val delivered = mutableListOf<Action>()
        val orchestrator = Dispatch(hid, bridge, delivered::add)
        val action = Action("reasoning_depth", delta = 1)

        assertTrue(orchestrator.activate(snapshot(action), INPUT_ID, Gesture.Kind.TAP))

        assertEquals(listOf(action), delivered)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun modelPickerUsesTheNativeHidShortcutWithoutBridgeFallback() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)

        assertTrue(orchestrator.openModel(snapshot(Action("approve"))))

        assertEquals(listOf(Action("model_picker")), hid.sent)
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
        assertTrue(bridge.calls.isEmpty())
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
        private val holdResult: Boolean = true,
    ) : Hid {
        val sent = mutableListOf<Action>()
        val held = mutableListOf<Action>()
        val released = mutableListOf<Action>()
        var releaseAllCount = 0

        override fun send(action: Action): Boolean {
            sent += action
            return sendResult
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

        override fun repeat(action: Action): Boolean = send(action)

        override fun stopRepeat() = Unit
    }

    private sealed interface BridgeCall {
        data class Activate(val inputId: String, val gesture: Gesture.Kind) : BridgeCall
        data class VoiceStart(val inputId: String) : BridgeCall
        data class VoiceStop(val inputId: String) : BridgeCall
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
            accessCycle = true,
            navigate = true,
            reasoning = true,
            workflow = true,
        )
    }
}
