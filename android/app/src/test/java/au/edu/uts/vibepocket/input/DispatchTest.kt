package au.edu.uts.vibepocket.input

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Agent
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Desktop
import au.edu.uts.vibepocket.control.Model
import au.edu.uts.vibepocket.control.Question
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Sources
import au.edu.uts.vibepocket.control.Status
import au.edu.uts.vibepocket.control.TargetRef
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
    fun rapidRepeatedTapIsGatedUntilItsHidCallbackCompletes() {
        val hid = FakeHid(deferSend = true)
        val action = Action("approve")
        val orchestrator = Dispatch(hid, FakeBridge())
        val snapshot = snapshot(action)

        assertTrue(orchestrator.activate(snapshot, INPUT_ID, Gesture.Kind.TAP))
        assertFalse(orchestrator.activate(snapshot, INPUT_ID, Gesture.Kind.TAP))
        assertEquals(listOf(action), hid.sent)

        hid.completeSend(HidResult.DELIVERED)

        assertTrue(orchestrator.activate(snapshot, INPUT_ID, Gesture.Kind.TAP))
        assertEquals(listOf(action, action), hid.sent)
    }

    @Test
    fun repeatableDirectionTapsRemainQueueableWhileACallbackIsPending() {
        val hid = FakeHid(deferSend = true)
        val action = Action("navigate", direction = "down")
        val orchestrator = Dispatch(hid, FakeBridge())
        val snapshot = snapshot(action)

        assertTrue(orchestrator.activate(snapshot, INPUT_ID, Gesture.Kind.TAP))
        assertTrue(orchestrator.activate(snapshot, INPUT_ID, Gesture.Kind.TAP))

        assertEquals(listOf(action, action), hid.sent)
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
    fun callbackFailureReportsAndRefreshesWhenSafeFallbackIsRejected() {
        val hid = FakeHid(deferSend = true)
        val bridge = FakeBridge(activateResult = false)
        val orchestrator = Dispatch(hid, bridge)

        assertTrue(orchestrator.activate(snapshot(Action("approve")), INPUT_ID, Gesture.Kind.TAP))

        hid.completeSend(HidResult.NOT_DISPATCHED)

        assertEquals(listOf("Bluetooth delivery failed."), bridge.deliveryFailures)
        assertEquals(1, bridge.refreshCount)
    }

    @Test
    fun indeterminateDeliveryIsReportedAndRefreshedWithoutBridgeFallback() {
        val hid = FakeHid(deliveryResult = HidResult.INDETERMINATE)
        val bridge = FakeBridge()
        val delivered = mutableListOf<Action>()
        val orchestrator = Dispatch(hid, bridge, delivered::add)

        assertTrue(orchestrator.activate(snapshot(Action("mode_cycle")), INPUT_ID, Gesture.Kind.TAP))

        assertTrue(delivered.isEmpty())
        assertTrue(bridge.calls.isEmpty())
        assertEquals(
            listOf("The Bluetooth action may have completed, but its outcome could not be confirmed."),
            bridge.deliveryFailures,
        )
        assertEquals(1, bridge.refreshCount)
    }

    @Test
    fun timedOutDeliveryIsReportedAsIndeterminateAndRefreshed() {
        val hid = FakeHid(deliveryResult = HidResult.TIMED_OUT)
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)

        assertTrue(orchestrator.activate(snapshot(Action("clear_input")), INPUT_ID, Gesture.Kind.TAP))

        assertTrue(bridge.calls.isEmpty())
        assertEquals(
            listOf("The Bluetooth action may have completed, but its outcome could not be confirmed."),
            bridge.deliveryFailures,
        )
        assertEquals(1, bridge.refreshCount)
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
    fun releaseAllowsANewActionAndLateCallbackCannotClearItsGate() {
        val hid = FakeHid(deferSend = true)
        val bridge = FakeBridge()
        val delivered = mutableListOf<Action>()
        val action = Action("approve")
        val snapshot = snapshot(action)
        val orchestrator = Dispatch(hid, bridge, delivered::add)

        assertTrue(orchestrator.activate(snapshot, INPUT_ID, Gesture.Kind.TAP))
        orchestrator.release()
        assertTrue(orchestrator.activate(snapshot, INPUT_ID, Gesture.Kind.TAP))

        hid.completeSend(HidResult.DELIVERED)

        assertFalse(orchestrator.activate(snapshot, INPUT_ID, Gesture.Kind.TAP))
        assertTrue(delivered.isEmpty())

        hid.completeSend(HidResult.DELIVERED)

        assertEquals(listOf(action), delivered)
        assertTrue(orchestrator.activate(snapshot, INPUT_ID, Gesture.Kind.TAP))
        assertEquals(listOf(action, action, action), hid.sent)
        assertTrue(bridge.deliveryFailures.isEmpty())
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
    fun rejectAlwaysUsesSemanticBridgeDelivery() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)

        assertTrue(orchestrator.activate(snapshot(Action("reject")), INPUT_ID, Gesture.Kind.TAP))

        assertTrue(hid.sent.isEmpty())
        assertEquals(listOf(BridgeCall.Activate(INPUT_ID, Gesture.Kind.TAP)), bridge.calls)
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
        assertTrue(hid.sent.isEmpty())
        assertEquals(listOf(BridgeCall.Activate(INPUT_ID, Gesture.Kind.TAP)), bridge.calls)
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
    fun ordinaryActionDuringHeldVoiceUsesBridgeWithoutTouchingTheChord() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)
        val voice = Action("voice")

        assertTrue(orchestrator.startVoice(snapshot(voice), INPUT_ID))
        assertTrue(orchestrator.activate(snapshot(Action("mode_cycle")), INPUT_ID, Gesture.Kind.TAP))

        assertTrue(hid.sent.isEmpty())
        assertTrue(hid.released.isEmpty())
        assertEquals(listOf(BridgeCall.Activate(INPUT_ID, Gesture.Kind.TAP)), bridge.calls)

        assertTrue(orchestrator.stopVoice(INPUT_ID))
        assertEquals(listOf(voice), hid.released)
    }

    @Test
    fun timedOutVoicePressIsOwnedAndDoesNotStartBridgeVoice() {
        val hid = FakeHid(holdResult = HidResult.TIMED_OUT)
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)
        val voice = Action("voice")

        assertTrue(orchestrator.startVoice(snapshot(voice), INPUT_ID))
        assertTrue(bridge.calls.isEmpty())
        assertTrue(orchestrator.stopVoice(INPUT_ID))
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

    @Test
    fun directTransitionFencesLateHidFallbackBeforeBridgeDelivery() {
        val hid = FakeHid(deferSend = true)
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)

        assertTrue(orchestrator.activate(snapshot(Action("mode_cycle")), INPUT_ID, Gesture.Kind.TAP))
        assertTrue(orchestrator.selectAgent(transitionSnapshot(Action("approve")), AGENT_B))
        hid.completeSend(false)

        assertEquals(listOf(BridgeCall.SelectAgent(AGENT_B)), bridge.calls)
        assertEquals(1, hid.stopRepeatCount)
        assertEquals(1, hid.quiesceCount)
    }

    @Test
    fun directTransitionStopsRepeatAndSuppressesItsLateFallback() {
        val hid = FakeHid(deferSend = true)
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)
        val snapshot = transitionSnapshot(Action("navigate", direction = "down"))

        assertTrue(orchestrator.startRepeat(snapshot, INPUT_ID))
        assertTrue(orchestrator.selectLayer(snapshot, "layer-2"))
        hid.completeSend(false)

        assertEquals(listOf(BridgeCall.SelectLayer("layer-2")), bridge.calls)
        assertEquals(1, hid.stopRepeatCount)
        assertEquals(1, hid.quiesceCount)
    }

    @Test
    fun backgroundModelReasoningAndModeUseExactBridgeCommandsWithoutHid() {
        val bridge = FakeBridge()
        val hid = FakeHid()
        val orchestrator = Dispatch(hid, bridge)
        val base = transitionSnapshot(Action("approve"))
        val desktop = requireNotNull(base.desktop)
        val background = base.copy(
            desktop = desktop.copy(
                foreground = false,
                mode = Selector(
                    available = true,
                    label = "Default",
                    id = "default",
                    options = listOf(Selector.Option("plan", "Plan", false)),
                ),
                reasoning = Reasoning(
                    available = true,
                    label = "High",
                    level = Reasoning.Level.HIGH,
                    canIncrease = true,
                    canDecrease = true,
                    options = listOf(Reasoning.Level.MEDIUM, Reasoning.Level.HIGH),
                ),
            ),
        )

        assertTrue(orchestrator.selectModel(background, "model-2"))
        assertFalse(orchestrator.selectMode(background, "plan"))
        assertTrue(orchestrator.selectReasoning(background, Reasoning.Level.MEDIUM))

        assertEquals(
            listOf(
                BridgeCall.SelectModel("model-2"),
                BridgeCall.SelectReasoning(Reasoning.Level.MEDIUM),
            ),
            bridge.calls,
        )
        assertTrue(hid.sent.isEmpty())
    }

    @Test
    fun reasoningDepthProfileActionUsesTheSemanticBridgePath() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)
        val state = transitionSnapshot(Action("reasoning_depth", delta = 1))

        assertTrue(orchestrator.activate(state, INPUT_ID, Gesture.Kind.TAP))

        assertEquals(listOf(BridgeCall.Activate(INPUT_ID, Gesture.Kind.TAP)), bridge.calls)
        assertTrue(hid.sent.isEmpty())
    }

    @Test
    fun heldVoiceRejectsTransitionWithoutRacingVoiceStop() {
        val hid = FakeHid()
        val bridge = FakeBridge()
        val orchestrator = Dispatch(hid, bridge)
        val snapshot = transitionSnapshot(Action("voice"))

        assertTrue(orchestrator.startVoice(snapshot, INPUT_ID))
        assertFalse(orchestrator.selectAgent(snapshot, AGENT_B))
        assertEquals(0, hid.quiesceCount)
        assertTrue(orchestrator.stopVoice(INPUT_ID))

        assertEquals(listOf(BridgeCall.VoiceStop(INPUT_ID)), bridge.calls)
        assertEquals(listOf(Action("voice")), hid.released)
    }

    @Test
    fun activeBarrierRejectsContextAndVoiceButKeepsUnrelatedGroupsResponsive() {
        val hid = FakeHid()
        val bridge = FakeBridge(transitionPending = true)
        val orchestrator = Dispatch(hid, bridge)
        val ordinary = transitionSnapshot(Action("mode_cycle"))

        assertFalse(orchestrator.activate(ordinary, INPUT_ID, Gesture.Kind.TAP))
        assertFalse(orchestrator.openModel(ordinary))
        assertTrue(orchestrator.startRepeat(transitionSnapshot(Action("navigate", direction = "up")), INPUT_ID))
        assertTrue(orchestrator.activate(transitionSnapshot(Action("approve")), INPUT_ID, Gesture.Kind.TAP))
        assertFalse(orchestrator.startVoice(transitionSnapshot(Action("voice")), INPUT_ID))
        assertFalse(orchestrator.selectAgent(ordinary, AGENT_B))
        assertTrue(orchestrator.stopVoice(INPUT_ID))
        orchestrator.release()

        assertEquals(
            listOf(Action("navigate", direction = "up"), Action("approve")),
            hid.sent,
        )
        assertEquals(listOf(BridgeCall.VoiceStop(INPUT_ID)), bridge.calls)
        assertEquals(1, hid.releaseAllCount)
    }

    private class FakeHid(
        private val sendResult: Boolean = true,
        private val deliveryResult: HidResult = HidResult.DELIVERED,
        private val deferSend: Boolean = false,
        private val holdResult: HidResult = HidResult.DELIVERED,
        private val releaseResult: HidResult = HidResult.DELIVERED,
    ) : Hid {
        val sent = mutableListOf<Action>()
        val held = mutableListOf<Action>()
        val released = mutableListOf<Action>()
        var releaseAllCount = 0
        var stopRepeatCount = 0
        var quiesceCount = 0
        private val sendCompletions = ArrayDeque<(HidResult) -> Unit>()

        override fun send(action: Action, completion: (HidResult) -> Unit): Boolean {
            sent += action
            if (!sendResult) return false
            if (deferSend) sendCompletions += completion else completion(deliveryResult)
            return true
        }

        override fun press(action: Action): HidResult {
            held += action
            return holdResult
        }

        override fun release(action: Action): HidResult {
            released += action
            return releaseResult
        }

        override fun releaseAny(): HidResult {
            releaseAllCount += 1
            return releaseResult
        }

        override fun repeat(action: Action, completion: (HidResult) -> Unit): Boolean = send(action, completion)

        override fun stopRepeat() {
            stopRepeatCount += 1
        }

        override fun quiesce(): Boolean {
            quiesceCount += 1
            return true
        }

        fun completeSend(delivered: Boolean) {
            completeSend(if (delivered) HidResult.DELIVERED else HidResult.NOT_DISPATCHED)
        }

        fun completeSend(result: HidResult) = sendCompletions.removeFirst()(result)
    }

    private sealed interface BridgeCall {
        data class Activate(val inputId: String, val gesture: Gesture.Kind) : BridgeCall
        data class VoiceStart(val inputId: String) : BridgeCall
        data class VoiceStop(val inputId: String) : BridgeCall
        data class SelectAgent(val agentId: String) : BridgeCall
        data class SelectModel(val modelId: String) : BridgeCall
        data class SelectMode(val modeId: String) : BridgeCall
        data class SelectReasoning(val level: Reasoning.Level) : BridgeCall
        data class SelectLayer(val layerId: String) : BridgeCall
        data object OpenModel : BridgeCall
    }

    private class FakeBridge(
        var transitionPending: Boolean = false,
        private val activateResult: Boolean = true,
    ) : Bridge {
        val calls = mutableListOf<BridgeCall>()
        val deliveryFailures = mutableListOf<String>()
        var refreshCount = 0

        override fun activate(inputId: String, gesture: Gesture.Kind): Boolean {
            calls += BridgeCall.Activate(inputId, gesture)
            return activateResult
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

        override fun contextTransitionPending(): Boolean = transitionPending

        override fun selectAgent(agentId: String): Boolean {
            calls += BridgeCall.SelectAgent(agentId)
            return true
        }

        override fun selectModel(modelId: String): Boolean {
            calls += BridgeCall.SelectModel(modelId)
            return true
        }

        override fun selectMode(modeId: String): Boolean {
            calls += BridgeCall.SelectMode(modeId)
            return true
        }

        override fun selectReasoning(level: Reasoning.Level): Boolean {
            calls += BridgeCall.SelectReasoning(level)
            return true
        }

        override fun selectLayer(layerId: String): Boolean {
            calls += BridgeCall.SelectLayer(layerId)
            return true
        }

        override fun reportLocalDeliveryFailure(message: String) {
            deliveryFailures += message
        }

        override fun reportLocalDeliveryIndeterminate(message: String) {
            deliveryFailures += message
            refreshCount += 1
        }

        override fun refresh() {
            refreshCount += 1
        }

        override fun observeSetting() {
            refreshCount += 1
        }
    }

    private fun transitionSnapshot(action: Action): Snapshot {
        val base = snapshot(action, capabilities = allCapabilities.copy(model = true))
        val desktop = requireNotNull(base.desktop)
        val profile = requireNotNull(desktop.profile)
        val secondLayer = Layer(
            id = "layer-2",
            name = "Second",
            color = "#000000",
            bindings = profile.layers.first().bindings,
        )
        val agents = listOf(
            Agent(AGENT_A, "Agent 1", Activity.IDLE, true),
            Agent(AGENT_B, "Agent 2", Activity.IDLE, false),
        )
        return base.copy(
            desktop = desktop.copy(
                profile = profile.copy(layers = profile.layers + secondLayer),
                agents = agents,
                focusedAgentIndex = 0,
                focusedAgentId = AGENT_A,
                model = Model(
                    available = true,
                    id = "model-1",
                    label = "Model 1",
                    options = listOf(Model.Option("model-2", "Model 2", false)),
                ),
            ),
        )
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
            binding = Desktop.Binding(
                Desktop.Binding.State.CONFIRMED,
                AGENT_A,
                Desktop.Binding.Target.bound(Target),
            ),
        ),
        transportFresh = transportFresh,
        sources = Sources(
            appServer = Sources.Source(true),
            desktopUI = Sources.Source(true),
        ),
    )

    private companion object {
        const val INPUT_ID = "key_test"
        const val AGENT_A = "agent-aaaaaaaaaaaaaaaaaaaaaaaa"
        const val AGENT_B = "agent-bbbbbbbbbbbbbbbbbbbbbbbb"
        val Target = TargetRef("thread-1", AGENT_A, 4, "bridge-1", 7, "workspace-1")
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
