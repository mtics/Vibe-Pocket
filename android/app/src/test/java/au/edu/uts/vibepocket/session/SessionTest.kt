package au.edu.uts.vibepocket.session

import androidx.lifecycle.ViewModelStore
import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.bridge.CommandResult
import au.edu.uts.vibepocket.bridge.CommandStatus
import au.edu.uts.vibepocket.bridge.EventCallbacks
import au.edu.uts.vibepocket.bridge.EventFactory
import au.edu.uts.vibepocket.bridge.EventStream
import au.edu.uts.vibepocket.bridge.Failure
import au.edu.uts.vibepocket.bridge.IssuedCredential
import au.edu.uts.vibepocket.bridge.ProtocolVersion
import au.edu.uts.vibepocket.bridge.decode
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.Claim
import au.edu.uts.vibepocket.connection.Invitation
import au.edu.uts.vibepocket.connection.PendingCommand
import au.edu.uts.vibepocket.connection.Store
import au.edu.uts.vibepocket.connection.VoiceStop
import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.Desktop
import au.edu.uts.vibepocket.control.Model
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Status
import au.edu.uts.vibepocket.control.Voice as VoiceState
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Binding
import au.edu.uts.vibepocket.profile.Choice
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.profile.Layer
import au.edu.uts.vibepocket.profile.Profile
import au.edu.uts.vibepocket.input.Plan
import au.edu.uts.vibepocket.input.activation
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun duplicateTapIsRejectedWhileDistinctCommandsQueueDurably() = runTest(dispatcher) {
        val config = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        val client = BlockingClient()
        val viewModel = Session(
            store = FakeStore(config),
            client = client,
            dispatcher = dispatcher,
        )
        runCurrent()

        assertTrue(viewModel.activateInput("key_accept", Gesture.Kind.TAP))
        assertFalse(viewModel.activateInput("key_accept", Gesture.Kind.TAP))
        assertTrue(viewModel.activateInput("key_voice", Gesture.Kind.TAP))
        runCurrent()
        assertEquals(
            listOf(
                Command.Binding(
                    "key_accept",
                    Gesture.Kind.TAP,
                    "layer-1",
                    Action("approve"),
                ),
            ),
            client.commands,
        )
        assertEquals(
            setOf("input:key_accept:tap", "input:key_voice:tap"),
            viewModel.state.value.inFlightIds,
        )

        client.commandRelease.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptySet<String>(), viewModel.state.value.inFlightIds)
    }

    @Test
    fun commandErrorIsNotOverwrittenWhenPendingIsPublished() = runTest(dispatcher) {
        val store = FakeStore(Config("https://m5.example.test", "0123456789abcdefghijklmn"))
        val viewModel = Session(
            store = store,
            client = RejectingCommandClient(),
            dispatcher = dispatcher,
        )
        runCurrent()

        assertTrue(viewModel.activateInput("key_accept"))
        runCurrent()

        assertEquals("The command was rejected for policy.", viewModel.state.value.error)
        assertTrue(viewModel.state.value.inFlightIds.isEmpty())
        assertEquals(null, store.pendingCommand)
    }

    @Test
    fun recreatedSessionRestoresPendingUiAndQueriesWithoutReplayingPost() = runTest(dispatcher) {
        val config = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        val firstStore = FakeStore(config)
        val firstClient = RecoverableCommandClient(CommandResult.Found(CommandStatus.RUNNING))
        val first = Session(store = firstStore, client = firstClient, dispatcher = dispatcher)
        runCurrent()

        assertTrue(first.activateInput("key_accept"))
        runCurrent()
        val persisted = requireNotNull(firstStore.pendingCommand)
        assertEquals(setOf("input:key_accept:tap"), first.state.value.inFlightIds)
        assertEquals("The command result is not yet confirmed.", first.state.value.error)

        val recreatedStore = firstStore.recreated()
        val recoveredClient = RecoverableCommandClient(CommandResult.Found(CommandStatus.SUCCEEDED))
        val recreated = Session(
            store = recreatedStore,
            client = recoveredClient,
            dispatcher = dispatcher,
        )

        assertEquals(setOf(persisted.uiId), recreated.state.value.inFlightIds)
        assertEquals("The command result is not yet confirmed.", recreated.state.value.error)
        runCurrent()

        assertEquals(0, recoveredClient.commandCalls)
        assertEquals(listOf(persisted.operationId), recoveredClient.queriedOperationIds)
        assertEquals(null, recreatedStore.pendingCommand)
        assertTrue(recreated.state.value.inFlightIds.isEmpty())
    }

    @Test
    fun unreadableCommandOutboxIsALifecycleErrorAndFailsClosed() = runTest(dispatcher) {
        val store = FakeStore(Config("https://m5.example.test", "0123456789abcdefghijklmn")).apply {
            pendingLoadFailure = IllegalStateException("cannot decrypt")
        }
        val client = StaticClient()
        val viewModel = Session(store = store, client = client, dispatcher = dispatcher)
        runCurrent()

        assertEquals(
            "Vibe Pocket could not read the pending command result.",
            viewModel.state.value.error,
        )
        assertFalse(viewModel.activateInput("key_accept"))
    }

    @Test
    fun savedSessionRejectsAnInitialSnapshotWithoutProtocolVersion() = runTest(dispatcher) {
        val client = object : Client {
            override suspend fun snapshot(config: Config): Snapshot =
                decode(JSONObject().put("revision", "r_missing"))

            override suspend fun command(config: Config, command: Command) = Unit
        }
        val viewModel = Session(
            store = FakeStore(DEVICE_CONFIG),
            client = client,
            dispatcher = dispatcher,
        )
        runCurrent()

        assertEquals(DEVICE_CONFIG, viewModel.state.value.config)
        assertEquals(null, viewModel.state.value.snapshot)
        assertFalse(viewModel.state.value.isRefreshing)
        assertEquals(
            "The Vibe Pocket bridge returned an incompatible snapshot protocol version.",
            viewModel.state.value.error,
        )
        assertFalse(viewModel.activateInput("key_accept"))
    }

    @Test
    fun repeatableControlsQueueEveryDurablePhysicalStep() = runTest(dispatcher) {
        val config = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        val client = BlockingClient()
        val viewModel = Session(
            store = FakeStore(config),
            client = client,
            dispatcher = dispatcher,
        )
        runCurrent()

        assertTrue(viewModel.activateInput("key_mode"))
        assertTrue(viewModel.activateInput("key_mode"))
        runCurrent()

        assertEquals(
            listOf(
                Command.Binding(
                    "key_mode",
                    Gesture.Kind.TAP,
                    "layer-1",
                    Action("mode_cycle"),
                ),
            ),
            client.commands,
        )
        assertEquals(2, viewModel.state.value.inFlightIds.size)
    }

    @Test
    fun backgroundRefreshKeepsAnAlreadyLoadedControllerInteractive() = runTest(dispatcher) {
        val config = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        val client = BlockingRefreshClient()
        val viewModel = Session(
            store = FakeStore(config),
            client = client,
            dispatcher = dispatcher,
        )
        runCurrent()

        assertEquals(VOICE_SNAPSHOT, viewModel.state.value.snapshot)
        assertFalse(viewModel.state.value.isRefreshing)

        viewModel.refresh()
        runCurrent()

        assertEquals(VOICE_SNAPSHOT, viewModel.state.value.snapshot)
        assertFalse(viewModel.state.value.isRefreshing)

        client.refreshRelease.complete(Unit)
        runCurrent()
    }

    @Test
    fun revisionAndMessageOnlyRefreshReplacesTheVisibleControllerSnapshot() = runTest(dispatcher) {
        val transportOnlyUpdate = VOICE_SNAPSHOT.copy(
            revision = "r_transport",
            status = Status("ready", "Adjusted the visible ChatGPT Codex control."),
        )
        val client = SnapshotQueueClient(VOICE_SNAPSHOT, transportOnlyUpdate)
        val viewModel = Session(
            store = FakeStore(Config("https://m5.example.test", "0123456789abcdefghijklmn")),
            client = client,
            dispatcher = dispatcher,
        )
        runCurrent()
        viewModel.refresh()
        runCurrent()

        assertEquals("r_transport", viewModel.state.value.snapshot?.revision)
        assertEquals(
            "Adjusted the visible ChatGPT Codex control.",
            viewModel.state.value.snapshot?.status?.message,
        )
    }

    @Test
    fun successfulManualRefreshRecoversARetainedCommandResult() = runTest(dispatcher) {
        val store = FakeStore(Config("https://m5.example.test", "0123456789abcdefghijklmn"))
        val client = RecoverableCommandClient(CommandResult.Found(CommandStatus.RUNNING))
        val viewModel = Session(store = store, client = client, dispatcher = dispatcher)
        runCurrent()

        assertTrue(viewModel.activateInput("key_accept"))
        runCurrent()
        val pending = requireNotNull(store.pendingCommand)
        assertEquals(listOf(pending.operationId), client.queriedOperationIds)

        client.result = CommandResult.Found(CommandStatus.SUCCEEDED)
        viewModel.refresh()
        runCurrent()

        assertEquals(listOf(pending.operationId, pending.operationId), client.queriedOperationIds)
        assertEquals(null, store.pendingCommand)
        assertTrue(viewModel.state.value.inFlightIds.isEmpty())
    }

    @Test
    fun deliveredHidReasoningStepIsImmediateAndSurvivesAStaleSnapshot() = runTest(dispatcher) {
        val confirmed = REASONING_SNAPSHOT.copy(
            revision = "r_confirmed",
            desktop = REASONING_SNAPSHOT.desktop?.copy(
                reasoning = REASONING_SNAPSHOT.desktop.reasoning.copy(
                    label = "High",
                    level = Reasoning.Level.HIGH,
                ),
            ),
        )
        val client = SnapshotQueueClient(
            REASONING_SNAPSHOT,
            REASONING_SNAPSHOT.copy(revision = "r_stale"),
            confirmed,
        )
        val viewModel = Session(
            store = FakeStore(Config("https://m5.example.test", "0123456789abcdefghijklmn")),
            client = client,
            dispatcher = dispatcher,
            nowMillis = { 1_000L },
        )
        runCurrent()

        viewModel.applyLocalAction(Action("reasoning_depth", delta = 1))
        assertEquals(Reasoning.Level.HIGH, viewModel.state.value.snapshot?.desktop?.reasoning?.level)

        viewModel.refresh()
        runCurrent()
        assertEquals(Reasoning.Level.HIGH, viewModel.state.value.snapshot?.desktop?.reasoning?.level)

        viewModel.refresh()
        runCurrent()
        assertEquals("High", viewModel.state.value.snapshot?.desktop?.reasoning?.label)
    }

    @Test
    fun transientMissingSelectorNeverReEnablesReasoningFromPrediction() = runTest(dispatcher) {
        val transient = REASONING_SNAPSHOT.copy(
            revision = "r_transient",
            capabilities = REASONING_SNAPSHOT.capabilities.copy(reasoning = false),
            desktop = REASONING_SNAPSHOT.desktop?.copy(reasoning = Reasoning.Unavailable),
        )
        val client = SnapshotQueueClient(REASONING_SNAPSHOT, transient)
        val viewModel = Session(
            store = FakeStore(Config("https://m5.example.test", "0123456789abcdefghijklmn")),
            client = client,
            dispatcher = dispatcher,
            nowMillis = { 1_000L },
        )
        runCurrent()

        viewModel.applyLocalAction(Action("reasoning_depth", delta = 1))
        viewModel.refresh()
        runCurrent()

        val visible = viewModel.state.value.snapshot
        assertFalse(visible?.capabilities?.reasoning == true)
        assertFalse(visible?.desktop?.reasoning?.available == true)
        assertEquals(null, visible?.desktop?.reasoning?.level)
    }

    @Test
    fun executingTaskCannotBeMadeAdjustableByAReasoningPrediction() = runTest(dispatcher) {
        val executing = REASONING_SNAPSHOT.copy(
            revision = "r_executing",
            capabilities = REASONING_SNAPSHOT.capabilities.copy(reasoning = false),
            desktop = REASONING_SNAPSHOT.desktop?.copy(
                activity = Activity.EXECUTING,
                reasoning = Reasoning.Unavailable,
            ),
        )
        val client = SnapshotQueueClient(REASONING_SNAPSHOT, executing)
        val viewModel = Session(
            store = FakeStore(Config("https://m5.example.test", "0123456789abcdefghijklmn")),
            client = client,
            dispatcher = dispatcher,
            nowMillis = { 1_000L },
        )
        runCurrent()

        viewModel.applyLocalAction(Action("reasoning_depth", delta = 1))
        viewModel.refresh()
        runCurrent()

        val visible = viewModel.state.value.snapshot
        assertFalse(visible?.capabilities?.reasoning == true)
        assertFalse(visible?.desktop?.reasoning?.available == true)
        assertEquals(null, visible?.desktop?.reasoning?.level)
    }

    @Test
    fun rapidVoiceReleaseQueuesStopBehindStart() = runTest(dispatcher) {
        val config = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        val client = BlockingVoiceClient()
        val viewModel = Session(
            store = FakeStore(config),
            client = client,
            dispatcher = dispatcher,
        )
        runCurrent()

        assertTrue(viewModel.startVoice("key_voice"))
        assertTrue(viewModel.stopVoice("key_voice"))
        runCurrent()
        assertEquals(listOf(Command.VoiceStart), client.commands)

        client.startRelease.complete(Unit)
        runCurrent()
        assertEquals(listOf(Command.VoiceStart, Command.VoiceStop), client.commands)
    }

    @Test
    fun lifecycleStopQueuesVoiceStopForCurrentOwner() = runTest(dispatcher) {
        val (viewModel, client) = voiceViewModel()
        runCurrent()

        assertTrue(viewModel.startVoice("key_voice"))
        viewModel.setForeground(false)
        runCurrent()
        assertEquals(listOf(Command.VoiceStart), client.commands)

        client.startRelease.complete(Unit)
        runCurrent()
        assertEquals(listOf(Command.VoiceStart, Command.VoiceStop), client.commands)
    }

    @Test
    fun disconnectQueuesVoiceStopForCurrentOwner() = runTest(dispatcher) {
        val (viewModel, client) = voiceViewModel()
        runCurrent()

        assertTrue(viewModel.startVoice("key_voice"))
        viewModel.disconnect()
        runCurrent()
        assertEquals(listOf(Command.VoiceStart), client.commands)

        client.startRelease.complete(Unit)
        runCurrent()
        assertEquals(listOf(Command.VoiceStart, Command.VoiceStop), client.commands)
    }

    @Test
    fun invalidConnectionUpdateKeepsTheSavedPairing() = runTest(dispatcher) {
        val original = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        val store = FakeStore(original)
        val viewModel = Session(
            store = store,
            client = SnapshotQueueClient(VOICE_SNAPSHOT),
            dispatcher = dispatcher,
        )
        runCurrent()

        assertFalse(viewModel.connect("http://not-secure.example.test", "0123456789abcdefghijklmn"))
        assertEquals(original, store.config)
        assertEquals(0, store.saveCalls)
        assertEquals(original, viewModel.state.value.config)
    }

    @Test
    fun validConnectionUpdatePersistsAndRefreshesTheNewPairing() = runTest(dispatcher) {
        val original = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        val replacement = Config("https://bridge.example.test", "zyxwvutsrqponmlkjihgfedc")
        val store = FakeStore(original)
        val viewModel = Session(
            store = store,
            client = SnapshotQueueClient(VOICE_SNAPSHOT, VOICE_SNAPSHOT),
            dispatcher = dispatcher,
        )
        runCurrent()

        assertTrue(viewModel.connect(replacement.baseUrl, replacement.credential))
        runCurrent()

        assertEquals(replacement, store.config)
        assertEquals(1, store.saveCalls)
        assertEquals(replacement, viewModel.state.value.config)
        assertEquals(VOICE_SNAPSHOT, viewModel.state.value.snapshot)
    }

    @Test
    fun failedCandidateVerificationKeepsTheOldPairingAndSession() = runTest(dispatcher) {
        val original = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        val replacement = Config("https://bridge.example.test", "zyxwvutsrqponmlkjihgfedc")
        val store = FakeStore(original)
        val client = FailingCandidateClient()
        val viewModel = Session(
            store = store,
            client = client,
            dispatcher = dispatcher,
        )
        runCurrent()

        assertTrue(viewModel.connect(replacement.baseUrl, replacement.credential))
        runCurrent()

        assertEquals(original, store.config)
        assertEquals(0, store.saveCalls)
        assertEquals(original, viewModel.state.value.config)
        assertEquals(VOICE_SNAPSHOT, viewModel.state.value.snapshot)
        assertEquals("Candidate bridge rejected the token.", viewModel.state.value.error)
    }

    @Test
    fun forgottenPairingCannotBeRestoredByALateCandidateVerification() = runTest(dispatcher) {
        val original = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        val replacement = Config("https://bridge.example.test", "zyxwvutsrqponmlkjihgfedc")
        val store = FakeStore(original)
        val client = BlockingCandidateClient()
        val viewModel = Session(
            store = store,
            client = client,
            dispatcher = dispatcher,
        )
        runCurrent()

        assertTrue(viewModel.connect(replacement.baseUrl, replacement.credential))
        runCurrent()
        viewModel.disconnect()
        client.candidateRelease.complete(Unit)
        runCurrent()

        assertEquals(null, store.config)
        assertEquals(0, store.saveCalls)
        assertEquals(1, store.clearCalls)
        assertEquals(null, viewModel.state.value.config)
        assertTrue(viewModel.state.value.inFlightIds.isEmpty())
    }

    @Test
    fun disconnectClearsTheSavedPairing() = runTest(dispatcher) {
        val config = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        val store = FakeStore(config)
        val viewModel = Session(
            store = store,
            client = SnapshotQueueClient(VOICE_SNAPSHOT),
            dispatcher = dispatcher,
        )
        runCurrent()

        viewModel.disconnect()

        assertEquals(null, store.config)
        assertEquals(1, store.clearCalls)
        assertEquals(null, viewModel.state.value.config)
    }

    @Test
    fun disconnectRetiresPendingCommandsBeforeANewPairingCanDispatch() = runTest(dispatcher) {
        val original = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        val replacement = Config("https://bridge.example.test", "zyxwvutsrqponmlkjihgfedc")
        val store = FakeStore(original)
        val client = BlockingClient()
        val viewModel = Session(store = store, client = client, dispatcher = dispatcher)
        runCurrent()

        assertTrue(viewModel.activateInput("key_accept"))
        runCurrent()
        assertEquals(1, store.pendingCommands.size)

        viewModel.disconnect()

        assertEquals(null, store.config)
        assertTrue(store.pendingCommands.isEmpty())
        assertTrue(viewModel.state.value.inFlightIds.isEmpty())
        assertFalse(viewModel.state.value.contextTransitionPending)

        assertTrue(viewModel.connect(replacement.baseUrl, replacement.credential))
        runCurrent()
        assertTrue(viewModel.activateInput("key_voice"))
        runCurrent()

        assertEquals(2, client.commands.size)
        assertEquals("voice", (client.commands.last() as Command.Binding).action.type)
    }

    @Test
    fun resetProfileSubmitsTheProfileResetCommand() = runTest(dispatcher) {
        val client = BlockingClient()
        val viewModel = Session(
            store = FakeStore(Config("https://m5.example.test", "0123456789abcdefghijklmn")),
            client = client,
            dispatcher = dispatcher,
        )
        runCurrent()

        assertTrue(viewModel.resetProfile())
        runCurrent()
        assertEquals(listOf(Command.ResetProfile), client.commands)

        client.commandRelease.complete(Unit)
        runCurrent()
    }

    @Test
    fun onClearedCancelsWorkerAndNextSessionRestoresVoiceStop() = runTest(dispatcher) {
        val disk = FakeStore(DEVICE_CONFIG)
        val client = BlockingVoiceClient()
        val viewModel = Session(store = disk, client = client, dispatcher = dispatcher)
        val owner = ViewModelStore().apply { put("voice", viewModel) }
        runCurrent()

        assertTrue(viewModel.startVoice("key_voice"))
        owner.clear()
        runCurrent()
        assertTrue(client.commands.isEmpty())
        val saved = requireNotNull(disk.voiceStop)

        client.startRelease.complete(Unit)
        runCurrent()
        assertTrue(client.commands.isEmpty())

        val recovery = RecreatedVoiceClient()
        Session(store = disk, client = recovery, dispatcher = dispatcher)
        runCurrent()
        assertEquals(listOf(saved), recovery.stops)
        assertEquals(null, disk.voiceStop)
    }

    @Test
    fun closedVoiceWorkerDoesNotRetryAfterViewModelRecreation() = runTest(dispatcher) {
        val disk = FakeStore(DEVICE_CONFIG)
        val client = RecreatedVoiceClient(stopFailures = 10)
        val viewModel = Session(store = disk, client = client, dispatcher = dispatcher)
        val owner = ViewModelStore().apply { put("voice", viewModel) }
        runCurrent()

        assertTrue(viewModel.startVoice("key_voice"))
        runCurrent()
        assertTrue(viewModel.stopVoice("key_voice"))
        runCurrent()
        assertEquals(1, client.stops.size)

        owner.clear()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, client.stops.size)
        assertTrue(disk.voiceStop != null)
    }

    @Test
    fun failedPairingCanRetryWithTheSamePhoneNonce() = runTest(dispatcher) {
        val store = FakeStore(null)
        val client = RetryPairClient()
        val viewModel = Session(store = store, client = client, dispatcher = dispatcher)
        val code = "a".repeat(43)

        assertTrue(viewModel.offer(invitation(code)))
        assertTrue(viewModel.pair())
        runCurrent()

        assertEquals("The first response was lost.", viewModel.state.value.error)
        assertTrue(viewModel.state.value.invitation != null)
        assertTrue(viewModel.pair())
        runCurrent()

        assertEquals(2, client.nonces.size)
        assertEquals(client.nonces.first(), client.nonces.last())
        assertEquals("https://m5.example.test", store.config?.normalizedUrl)
        assertEquals(null, viewModel.state.value.invitation)
    }

    @Test
    fun sseFailureKeepsDisplayContentButForcesBridgeFallback() = runTest(dispatcher) {
        val events = FakeEvents()
        val viewModel = Session(
            store = FakeStore(Config("https://m5.example.test", "0123456789abcdefghijklmn")),
            client = StaticClient(),
            dispatcher = dispatcher,
            eventFactory = events,
        )
        runCurrent()
        viewModel.setForeground(true)

        events.callbacks.disconnected("Event stream lost.")

        val stale = requireNotNull(viewModel.state.value.snapshot)
        assertFalse(stale.transportFresh)
        assertEquals("Default", stale.desktop?.profile?.layers?.first()?.name)
        assertTrue(activation(stale, "key_accept", Gesture.Kind.TAP) is Plan.Bridge)

        events.callbacks.connected()
        val connected = requireNotNull(viewModel.state.value.snapshot)
        assertFalse(connected.transportFresh)
        assertTrue(activation(connected, "key_accept", Gesture.Kind.TAP) is Plan.Bridge)

        runCurrent()
        val fresh = requireNotNull(viewModel.state.value.snapshot)
        assertTrue(fresh.transportFresh)
        assertTrue(activation(fresh, "key_accept", Gesture.Kind.TAP) is Plan.HidTap)
    }

    @Test
    fun sseRefreshRejectsAProtocolChangeAndDoesNotUseItsCommands() = runTest(dispatcher) {
        val initial = reasoningSnapshot("agent-a", "model-a").copy(
            revision = "r_compatible",
            capabilities = REASONING_SNAPSHOT.capabilities.copy(model = true),
        )
        val client = ProtocolChangingClient(initial)
        val events = FakeEvents()
        val viewModel = Session(
            store = FakeStore(DEVICE_CONFIG),
            client = client,
            dispatcher = dispatcher,
            eventFactory = events,
        )
        runCurrent()
        assertTrue(viewModel.state.value.snapshot?.transportFresh == true)

        viewModel.setForeground(true)
        events.callbacks.snapshotChanged()
        runCurrent()

        val visible = requireNotNull(viewModel.state.value.snapshot)
        assertEquals("r_compatible", visible.revision)
        assertEquals("model-a", visible.desktop?.model?.id)
        assertFalse(visible.transportFresh)
        assertEquals(
            "The Vibe Pocket bridge returned an incompatible snapshot protocol version.",
            viewModel.state.value.error,
        )
        assertFalse(viewModel.selectModel("future-model"))
        assertTrue(client.commands.isEmpty())
    }

    @Test
    fun predictionExpiryRefreshesWithoutAnSseEvent() = runTest(dispatcher) {
        val client = CountingSnapshotClient(REASONING_SNAPSHOT, REASONING_SNAPSHOT)
        val viewModel = Session(
            store = FakeStore(Config("https://m5.example.test", "0123456789abcdefghijklmn")),
            client = client,
            dispatcher = dispatcher,
            nowMillis = { testScheduler.currentTime },
        )
        runCurrent()

        viewModel.applyLocalAction(Action("reasoning_depth", delta = 1))
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(1, client.snapshotCalls)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, client.snapshotCalls)
        assertEquals(Reasoning.Level.MEDIUM, viewModel.state.value.snapshot?.desktop?.reasoning?.level)
    }

    @Test
    fun confirmedPredictionCancelsItsExpiryRefresh() = runTest(dispatcher) {
        val confirmed = REASONING_SNAPSHOT.copy(
            desktop = REASONING_SNAPSHOT.desktop?.copy(
                reasoning = REASONING_SNAPSHOT.desktop.reasoning.copy(level = Reasoning.Level.HIGH),
            ),
        )
        val client = CountingSnapshotClient(REASONING_SNAPSHOT, confirmed)
        val viewModel = Session(
            store = FakeStore(Config("https://m5.example.test", "0123456789abcdefghijklmn")),
            client = client,
            dispatcher = dispatcher,
            nowMillis = { testScheduler.currentTime },
        )
        runCurrent()

        viewModel.applyLocalAction(Action("reasoning_depth", delta = 1))
        viewModel.refresh()
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(2, client.snapshotCalls)
    }

    @Test
    fun localDeliveryFailureClearsPredictionImmediately() = runTest(dispatcher) {
        val viewModel = Session(
            store = FakeStore(Config("https://m5.example.test", "0123456789abcdefghijklmn")),
            client = StaticClient(REASONING_SNAPSHOT),
            dispatcher = dispatcher,
            nowMillis = { testScheduler.currentTime },
        )
        runCurrent()

        viewModel.applyLocalAction(Action("reasoning_depth", delta = 1))
        assertEquals(Reasoning.Level.HIGH, viewModel.state.value.snapshot?.desktop?.reasoning?.level)

        viewModel.reportLocalDeliveryFailure("Bluetooth delivery failed.")

        assertEquals(Reasoning.Level.MEDIUM, viewModel.state.value.snapshot?.desktop?.reasoning?.level)
    }

    @Test
    fun reasoningPredictionDoesNotCrossAgentOrModelIdentity() = runTest(dispatcher) {
        val first = reasoningSnapshot("agent-a", "model-a")
        val second = reasoningSnapshot("agent-b", "model-b")
        val client = SnapshotQueueClient(first, second)
        val viewModel = Session(
            store = FakeStore(DEVICE_CONFIG),
            client = client,
            dispatcher = dispatcher,
            nowMillis = { testScheduler.currentTime },
        )
        runCurrent()

        viewModel.applyLocalAction(Action("reasoning_depth", delta = 1))
        assertEquals(Reasoning.Level.HIGH, viewModel.state.value.snapshot?.desktop?.reasoning?.level)

        viewModel.refresh()
        runCurrent()

        val visible = requireNotNull(viewModel.state.value.snapshot)
        assertEquals("agent-b", visible.desktop?.focusedAgentId)
        assertEquals("model-b", visible.desktop?.model?.id)
        assertEquals(Reasoning.Level.MEDIUM, visible.desktop?.reasoning?.level)
    }

    @Test
    fun newerInvitationSupersedesAnInFlightClaim() = runTest(dispatcher) {
        val store = FakeStore(null)
        val client = SupersedingPairClient()
        val viewModel = Session(store = store, client = client, dispatcher = dispatcher)
        val firstCode = "a".repeat(43)
        val secondCode = "b".repeat(43)

        assertTrue(viewModel.offer(invitation(firstCode)))
        assertTrue(viewModel.pair())
        runCurrent()
        assertTrue(viewModel.offer(invitation(secondCode)))
        assertTrue(viewModel.pair())
        runCurrent()

        assertEquals(client.secondConfig, store.config)
        client.firstRelease.complete(Unit)
        runCurrent()

        assertEquals(client.secondConfig, store.config)
        assertEquals(null, store.claim)
        assertEquals(listOf(client.firstConfig), client.revocations)
    }

    @Test
    fun dismissedInFlightClaimRevokesALateIssuedCredential() = runTest(dispatcher) {
        val store = FakeStore(null)
        val client = SupersedingPairClient()
        val viewModel = Session(store = store, client = client, dispatcher = dispatcher)
        val code = "a".repeat(43)

        assertTrue(viewModel.offer(invitation(code)))
        assertTrue(viewModel.pair())
        runCurrent()
        viewModel.dismissPairing()

        client.firstRelease.complete(Unit)
        runCurrent()

        assertEquals(null, store.config)
        assertEquals(null, store.claim)
        assertEquals(listOf(client.firstConfig), client.revocations)
    }

    @Test
    fun recreatedSessionRetriesPersistedClaimNonce() = runTest(dispatcher) {
        val store = FakeStore(null)
        val client = RecreatedPairClient()
        val code = "c".repeat(43)
        val first = Session(store = store, client = client, dispatcher = dispatcher)

        assertTrue(first.offer(invitation(code)))
        assertTrue(first.pair())
        runCurrent()
        val savedNonce = requireNotNull(store.claim).nonce

        val recreated = Session(store = store, client = client, dispatcher = dispatcher)
        assertEquals(code, recreated.state.value.invitation?.code)
        assertTrue(recreated.pair())
        runCurrent()

        assertEquals(listOf(savedNonce, savedNonce), client.nonces)
        assertEquals(null, store.claim)
        assertEquals(client.config, store.config)
    }

    @Test
    fun activationResponseLossRetriesIssuedTokenWithoutReclaiming() = runTest(dispatcher) {
        val store = FakeStore(null)
        val client = ActivationLossPairClient()
        val viewModel = Session(store = store, client = client, dispatcher = dispatcher)

        assertTrue(viewModel.offer(invitation("d".repeat(43))))
        assertTrue(viewModel.pair())
        runCurrent()

        assertEquals(1, client.claimCalls)
        assertEquals(1, client.activateCalls)
        assertEquals(client.config, store.claim?.issued)
        assertTrue(viewModel.pair())
        runCurrent()

        assertEquals(1, client.claimCalls)
        assertEquals(2, client.activateCalls)
        assertEquals(client.config, store.config)
        assertEquals(null, store.claim)
    }

    @Test
    fun restoredIssuedReplacementIsVisibleAndSkipsClaimWithOldConfig() = runTest(dispatcher) {
        val old = Config("https://old.example.test", "vp1.oldphone.abcdefghijklmnopqrstuvwxyzABCDEFG")
        val replacement = Config("https://m5.example.test", "vp1.pending.abcdefghijklmnopqrstuvwxyzABCDEFG")
        val pendingInvitation = Invitation("https://m5.example.test", "e".repeat(43), Long.MAX_VALUE)
        val store = FakeStore(old).also {
            it.claim = Claim(pendingInvitation, "nonce", replacement, Long.MAX_VALUE)
        }
        val client = PersistedIssuedClient()
        val viewModel = Session(store = store, client = client, dispatcher = dispatcher)

        assertEquals(old, viewModel.state.value.config)
        assertEquals(pendingInvitation, viewModel.state.value.invitation)
        assertTrue(viewModel.pair())
        runCurrent()

        assertEquals(0, client.claimCalls)
        assertEquals(1, client.activateCalls)
        assertEquals(replacement, store.config)
    }

    @Test
    fun dismissalAndSupersessionQueueIssuedTokenBeforeClaimClear() = runTest(dispatcher) {
        val issued = Config("https://m5.example.test", "vp1.pending.abcdefghijklmnopqrstuvwxyzABCDEFG")
        val firstInvitation = Invitation("https://m5.example.test", "f".repeat(43), Long.MAX_VALUE)
        val firstStore = FakeStore(DEVICE_CONFIG).also {
            it.claim = Claim(firstInvitation, "nonce", issued, Long.MAX_VALUE)
        }
        val first = Session(store = firstStore, client = StaticClient(), dispatcher = dispatcher)

        assertTrue(first.dismissPairing())
        assertEquals(listOf("revocation:save", "claim:clear"), firstStore.lifecycleEvents.take(2))
        assertEquals(issued, firstStore.revocation)

        val secondStore = FakeStore(DEVICE_CONFIG).also {
            it.claim = Claim(firstInvitation, "nonce", issued, Long.MAX_VALUE)
        }
        val second = Session(store = secondStore, client = StaticClient(), dispatcher = dispatcher)
        val replacementCode = "g".repeat(43)

        assertTrue(second.offer(invitation(replacementCode)))
        assertEquals(listOf("revocation:save", "claim:clear"), secondStore.lifecycleEvents.take(2))
        assertEquals(replacementCode, second.state.value.invitation?.code)
        assertEquals(DEVICE_CONFIG, second.state.value.config)
    }

    @Test
    fun expiredPendingCredentialIsRetiredWithoutReplacingOldConfig() = runTest(dispatcher) {
        val store = FakeStore(DEVICE_CONFIG)
        val client = ExpiredPairClient()
        val viewModel = Session(
            store = store,
            client = client,
            dispatcher = dispatcher,
            nowMillis = { 100L },
        )
        val owner = ViewModelStore().apply { put("expired-pair", viewModel) }

        assertTrue(viewModel.offer(invitation("h".repeat(43))))
        assertTrue(viewModel.pair())
        runCurrent()

        assertEquals(0, client.activateCalls)
        assertEquals(DEVICE_CONFIG, store.config)
        assertEquals(null, store.claim)
        assertEquals(client.config, store.revocation)
        owner.clear()
    }

    @Test
    fun unauthorizedActivationRetiresPendingTokenWithoutDestroyingOldConfig() = runTest(dispatcher) {
        val store = FakeStore(DEVICE_CONFIG)
        val client = UnauthorizedActivationPairClient()
        val viewModel = Session(store = store, client = client, dispatcher = dispatcher)
        val owner = ViewModelStore().apply { put("unauthorized-pair", viewModel) }

        assertTrue(viewModel.offer(invitation("i".repeat(43))))
        assertTrue(viewModel.pair())
        runCurrent()

        assertEquals(DEVICE_CONFIG, store.config)
        assertEquals(null, store.claim)
        assertEquals(client.config, store.revocation)
        assertEquals(401, (client.activationFailure as Failure).statusCode)
        owner.clear()
    }

    @Test
    fun pairingPersistsIssuedTokenBeforeActivationAndCommitsAfterSnapshot() = runTest(dispatcher) {
        val trace = mutableListOf<String>()
        val store = FakeStore(null, trace)
        val client = OrderedPairClient(trace)
        val viewModel = Session(store = store, client = client, dispatcher = dispatcher)

        assertTrue(viewModel.offer(invitation("j".repeat(43))))
        assertTrue(viewModel.pair())
        runCurrent()

        assertEquals(
            listOf("claim:empty", "claim", "claim:issued", "activate", "snapshot", "commit"),
            trace,
        )
    }

    @Test
    fun revocationTombstoneSurvivesSlowDeleteAndRetriesOnStartup() = runTest(dispatcher) {
        val config = DEVICE_CONFIG
        val store = FakeStore(config)
        val client = RestartRevocationClient()
        val first = Session(store = store, client = client, dispatcher = dispatcher)
        val owner = ViewModelStore().apply { put("first", first) }
        runCurrent()

        first.disconnect()
        runCurrent()
        assertEquals(config, store.revocation)
        assertEquals(null, store.config)
        assertEquals(1, client.revokeCalls)

        owner.clear()
        val recreated = Session(store = store, client = client, dispatcher = dispatcher)
        runCurrent()

        assertEquals(2, client.revokeCalls)
        assertEquals(null, store.revocation)
        assertEquals(null, recreated.state.value.config)
    }

    @Test
    fun slowRevocationTimesOutAndRetriesWithoutDroppingTombstone() = runTest(dispatcher) {
        val config = DEVICE_CONFIG
        val replacement = Config("https://other.example.test", "zyxwvutsrqponmlkjihgfedc")
        val store = FakeStore(config)
        val client = RestartRevocationClient()
        val viewModel = Session(
            store = store,
            client = client,
            dispatcher = dispatcher,
            retry = Retry(timeoutMillis = 100, initialDelayMillis = 10, maxDelayMillis = 10),
        )
        runCurrent()

        viewModel.disconnect()
        runCurrent()
        assertEquals(config, store.revocation)
        assertEquals(1, client.revokeCalls)
        assertTrue(viewModel.connect(replacement.baseUrl, replacement.credential))
        runCurrent()
        assertEquals(replacement, store.config)
        assertEquals(config, store.revocation)

        advanceTimeBy(100)
        runCurrent()
        assertEquals(config, store.revocation)

        advanceTimeBy(10)
        runCurrent()
        assertEquals(2, client.revokeCalls)
        assertEquals(null, store.revocation)
    }

    @Test
    fun unauthorizedRevocationResponseClearsAnAlreadyFulfilledTombstone() = runTest(dispatcher) {
        val store = FakeStore(DEVICE_CONFIG)
        val client = AlreadyRevokedClient()
        val viewModel = Session(store = store, client = client, dispatcher = dispatcher)
        runCurrent()

        viewModel.disconnect()
        runCurrent()

        assertEquals(1, client.revokeCalls)
        assertEquals(null, store.revocation)
    }

    @Test
    fun lostStartResponseStillStopsOnlyAfterStartCompletes() = runTest(dispatcher) {
        val client = LostVoiceClient()
        val viewModel = Session(
            store = FakeStore(Config("https://m5.example.test", "0123456789abcdefghijklmn")),
            client = client,
            dispatcher = dispatcher,
        )
        runCurrent()

        assertTrue(viewModel.startVoice("key_voice"))
        assertTrue(viewModel.stopVoice("key_voice"))
        runCurrent()
        assertEquals(0, client.stopKeys.size)

        client.startLost.complete(Unit)
        runCurrent()

        assertEquals(1, client.stopKeys.size)
        assertEquals(1, client.stopKeys.toSet().size)
    }

    @Test
    fun activeVoiceCanBeStoppedThroughARemappedInput() = runTest(dispatcher) {
        val (viewModel, client) = voiceViewModel()
        runCurrent()

        assertTrue(viewModel.startVoice("key_voice"))
        assertTrue(viewModel.stopVoice("key_remapped_voice"))
        runCurrent()
        assertEquals(listOf(Command.VoiceStart), client.commands)

        client.startRelease.complete(Unit)
        runCurrent()
        assertEquals(listOf(Command.VoiceStart, Command.VoiceStop), client.commands)
    }

    @Test
    fun externalHidVoiceStopCreatesADurableSemanticFallback() = runTest(dispatcher) {
        val disk = FakeStore(DEVICE_CONFIG)
        val client = RecreatedVoiceClient()
        val viewModel = Session(store = disk, client = client, dispatcher = dispatcher)
        runCurrent()

        assertTrue(viewModel.stopVoice("key_voice"))
        runCurrent()

        assertEquals(1, client.stops.size)
        assertEquals(null, disk.voiceStop)
    }

    @Test
    fun recreatedSessionStopsVoiceAfterAcceptedStart() = runTest(dispatcher) {
        val disk = FakeStore(DEVICE_CONFIG)
        val firstClient = RecreatedVoiceClient()
        val first = Session(store = disk, client = firstClient, dispatcher = dispatcher)
        runCurrent()

        assertTrue(first.startVoice("key_voice"))
        runCurrent()
        val saved = requireNotNull(disk.voiceStop)
        assertEquals(DEVICE_CONFIG, saved.config)

        val recreatedDisk = disk.recreated()
        val recreatedClient = RecreatedVoiceClient()
        Session(store = recreatedDisk, client = recreatedClient, dispatcher = dispatcher)
        runCurrent()

        assertEquals(listOf(saved), recreatedClient.stops)
        assertEquals(null, recreatedDisk.voiceStop)
    }

    @Test
    fun recreatedSessionStopsVoiceAfterLostStartResponse() = runTest(dispatcher) {
        val disk = FakeStore(DEVICE_CONFIG)
        val firstClient = RecreatedVoiceClient(startRelease = CompletableDeferred())
        val first = Session(store = disk, client = firstClient, dispatcher = dispatcher)
        runCurrent()

        assertTrue(first.startVoice("key_voice"))
        runCurrent()
        val saved = requireNotNull(disk.voiceStop)
        assertEquals(listOf(DEVICE_CONFIG), firstClient.starts)

        val recreatedDisk = disk.recreated()
        val recreatedClient = RecreatedVoiceClient()
        Session(store = recreatedDisk, client = recreatedClient, dispatcher = dispatcher)
        runCurrent()

        assertEquals(listOf(saved), recreatedClient.stops)
        assertEquals(null, recreatedDisk.voiceStop)
    }

    @Test
    fun replacementKeepsFailedStopBoundToOriginalCredentialAfterRecreation() = runTest(dispatcher) {
        val replacement = Config(
            "https://replacement.example.test",
            "vp1.phone456.abcdefghijklmnopqrstuvwxyzABCDEF",
        )
        val disk = FakeStore(DEVICE_CONFIG)
        val firstClient = RecreatedVoiceClient(stopFailures = 1)
        val first = Session(store = disk, client = firstClient, dispatcher = dispatcher)
        runCurrent()

        assertTrue(first.startVoice("key_voice"))
        runCurrent()
        assertTrue(first.connect(replacement.baseUrl, replacement.credential))
        runCurrent()

        val saved = requireNotNull(disk.voiceStop)
        assertEquals(DEVICE_CONFIG, saved.config)
        assertEquals(replacement, disk.config)
        assertEquals(listOf(saved), firstClient.stops)

        val recreatedDisk = disk.recreated()
        val recreatedClient = RecreatedVoiceClient()
        Session(store = recreatedDisk, client = recreatedClient, dispatcher = dispatcher)
        runCurrent()

        assertEquals(replacement, recreatedDisk.config)
        assertEquals(listOf(saved), recreatedClient.stops)
        assertEquals(null, recreatedDisk.voiceStop)
    }

    @Test
    fun revocationWaitsForPersistedVoiceStopAcknowledgement() = runTest(dispatcher) {
        val disk = FakeStore(DEVICE_CONFIG)
        val stopRelease = CompletableDeferred<Unit>()
        val client = RecreatedVoiceClient(stopRelease = stopRelease)
        val viewModel = Session(store = disk, client = client, dispatcher = dispatcher)
        runCurrent()

        assertTrue(viewModel.startVoice("key_voice"))
        runCurrent()
        viewModel.disconnect()
        runCurrent()

        assertEquals(0, client.revocations.size)
        assertEquals(DEVICE_CONFIG, disk.revocation)
        assertTrue(disk.voiceStop != null)

        stopRelease.complete(Unit)
        runCurrent()
        assertEquals(null, disk.voiceStop)
        advanceTimeBy(250)
        runCurrent()

        assertEquals(listOf(DEVICE_CONFIG), client.revocations)
        assertEquals(null, disk.revocation)
    }

    private fun voiceViewModel(): Pair<Session, BlockingVoiceClient> {
        val config = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        val client = BlockingVoiceClient()
        return Session(
            store = FakeStore(config),
            client = client,
            dispatcher = dispatcher,
        ) to client
    }

    private class FakeStore(
        config: Config?,
        private val trace: MutableList<String>? = null,
    ) : Store {
        var config: Config? = config
            private set
        var saveCalls = 0
            private set
        var clearCalls = 0
            private set
        var claim: Claim? = null
        var revocation: Config? = null
        var voiceStop: VoiceStop? = null
        val pendingCommands = mutableListOf<PendingCommand>()
        var pendingCommand: PendingCommand?
            get() = pendingCommands.firstOrNull()
            set(value) {
                pendingCommands.clear()
                value?.let(pendingCommands::add)
            }
        var pendingLoadFailure: Throwable? = null
        val lifecycleEvents = mutableListOf<String>()

        override fun save(config: Config) {
            this.config = config
            saveCalls += 1
        }

        override fun load(): Config? = config

        override fun clear() {
            config = null
            clearCalls += 1
        }

        override fun saveClaim(claim: Claim) {
            trace?.add(if (claim.issued == null) "claim:empty" else "claim:issued")
            this.claim = claim
        }

        override fun loadClaim(): Claim? = claim

        override fun clearClaim() {
            lifecycleEvents += "claim:clear"
            claim = null
        }

        override fun saveRevocation(config: Config) {
            lifecycleEvents += "revocation:save"
            revocation = config
        }

        override fun loadRevocation(): Config? = revocation

        override fun clearRevocation() {
            revocation = null
        }

        override fun saveVoiceStop(stop: VoiceStop) {
            voiceStop = stop
        }

        override fun loadVoiceStop(): VoiceStop? = voiceStop

        override fun clearVoiceStop(idempotencyKey: String): Boolean {
            val current = voiceStop ?: return true
            if (current.idempotencyKey != idempotencyKey) return false
            voiceStop = null
            return true
        }

        override fun savePendingCommand(command: PendingCommand) {
            pendingCommands += command
        }

        override fun loadPendingCommand(): PendingCommand? {
            pendingLoadFailure?.let { throw it }
            return pendingCommand
        }

        override fun loadPendingCommands(): List<PendingCommand> {
            pendingLoadFailure?.let { throw it }
            return pendingCommands.toList()
        }

        override fun markPendingCommandDispatched(operationId: String): PendingCommand {
            val current = pendingCommand
                ?.takeIf { it.operationId == operationId }
                ?: error("Different pending command")
            return current.copy(phase = PendingCommand.Phase.DISPATCH_ATTEMPTED).also {
                pendingCommands[0] = it
            }
        }

        override fun clearPendingCommand(operationId: String): Boolean {
            val current = pendingCommand ?: return true
            if (current.operationId != operationId) return false
            pendingCommands.removeAt(0)
            return true
        }

        override fun commit(config: Config) {
            trace?.add("commit")
            this.config = config
            saveCalls += 1
            claim = null
        }

        fun recreated(): FakeStore = FakeStore(config).also {
            it.claim = claim
            it.revocation = revocation
            it.voiceStop = voiceStop
            it.pendingCommands.addAll(pendingCommands)
            it.pendingLoadFailure = pendingLoadFailure
        }
    }

    private class BlockingClient : Client {
        val commands = mutableListOf<Command>()
        val commandRelease = CompletableDeferred<Unit>()

        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT

        override suspend fun command(config: Config, command: Command) {
            commands += command
            commandRelease.await()
        }
    }

    private class RejectingCommandClient : Client {
        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT

        override suspend fun command(config: Config, command: Command) {
            throw Failure("The command was rejected for policy.", 409, "policy_rejected")
        }
    }

    private class RecoverableCommandClient(
        var result: CommandResult,
    ) : Client {
        var commandCalls = 0
        val queriedOperationIds = mutableListOf<String>()

        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT
        override suspend fun command(config: Config, command: Command) =
            error("Explicit operation ID required")

        override suspend fun command(config: Config, command: Command, operationId: String) {
            commandCalls += 1
            throw IOException("POST response lost")
        }

        override suspend fun commandResult(config: Config, operationId: String): CommandResult {
            queriedOperationIds += operationId
            return result
        }
    }

    private class BlockingVoiceClient : Client {
        val commands = mutableListOf<Command>()
        val startRelease = CompletableDeferred<Unit>()

        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT

        override suspend fun command(config: Config, command: Command) {
            commands += command
            if (command == Command.VoiceStart) startRelease.await()
        }
    }

    private class BlockingRefreshClient : Client {
        var snapshotCalls = 0
        val refreshRelease = CompletableDeferred<Unit>()

        override suspend fun snapshot(config: Config): Snapshot {
            snapshotCalls += 1
            if (snapshotCalls > 1) refreshRelease.await()
            return VOICE_SNAPSHOT
        }

        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class SnapshotQueueClient(
        vararg snapshots: Snapshot,
    ) : Client {
        private val queuedSnapshots = ArrayDeque(snapshots.toList())

        override suspend fun snapshot(config: Config): Snapshot = queuedSnapshots.removeFirst()

        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class ProtocolChangingClient(
        private val initial: Snapshot,
    ) : Client {
        private var snapshotCalls = 0
        val commands = mutableListOf<Command>()

        override suspend fun snapshot(config: Config): Snapshot {
            snapshotCalls += 1
            if (snapshotCalls == 1) return initial
            return decode(
                JSONObject()
                    .put("protocolVersion", ProtocolVersion + 1)
                    .put("revision", "r_incompatible")
                    .put("status", JSONObject().put("state", "ready"))
                    .put("controls", JSONObject().put("model", true))
                    .put(
                        "controller",
                        JSONObject()
                            .put("foreground", true)
                            .put(
                                "model",
                                JSONObject()
                                    .put("available", true)
                                    .put("id", "model-a")
                                    .put(
                                        "options",
                                        JSONArray()
                                            .put(JSONObject().put("id", "model-a").put("selected", true))
                                            .put(JSONObject().put("id", "future-model")),
                                    ),
                            ),
                    ),
            )
        }

        override suspend fun command(config: Config, command: Command) {
            commands += command
        }
    }

    private class FailingCandidateClient : Client {
        private var snapshotCalls = 0

        override suspend fun snapshot(config: Config): Snapshot {
            snapshotCalls += 1
            if (snapshotCalls == 1) return VOICE_SNAPSHOT
            throw Failure("Candidate bridge rejected the token.")
        }

        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class BlockingCandidateClient : Client {
        private var snapshotCalls = 0
        val candidateRelease = CompletableDeferred<Unit>()

        override suspend fun snapshot(config: Config): Snapshot {
            snapshotCalls += 1
            if (snapshotCalls > 1) candidateRelease.await()
            return VOICE_SNAPSHOT
        }

        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class RetryPairClient : Client {
        val nonces = mutableListOf<String>()

        override suspend fun claim(invitation: Invitation, nonce: String): Config {
            nonces += nonce
            if (nonces.size == 1) throw Failure("The first response was lost.")
            return Config(invitation.origin, "vp1.testdevice.abcdefghijklmnopqrstuvwxyzABCDEFG")
        }

        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT
        override suspend fun activate(config: Config) = Unit

        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class StaticClient(
        private val value: Snapshot = VOICE_SNAPSHOT,
    ) : Client {
        override suspend fun snapshot(config: Config): Snapshot = value
        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class CountingSnapshotClient(
        vararg snapshots: Snapshot,
    ) : Client {
        private val values = ArrayDeque(snapshots.toList())
        var snapshotCalls = 0
            private set

        override suspend fun snapshot(config: Config): Snapshot {
            snapshotCalls += 1
            return values.removeFirst()
        }

        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class FakeEvents : EventFactory {
        lateinit var callbacks: EventCallbacks

        override fun create(
            config: Config,
            lastEventId: String?,
            callbacks: EventCallbacks,
        ): EventStream {
            this.callbacks = callbacks
            return object : EventStream {
                override fun start() = Unit
                override fun stop() = Unit
            }
        }
    }

    private class SupersedingPairClient : Client {
        val firstRelease = CompletableDeferred<Unit>()
        val firstConfig = Config("https://m5.example.test", "vp1.first.abcdefghijklmnopqrstuvwxyzABCDEFG")
        val secondConfig = Config("https://m5.example.test", "vp1.second.abcdefghijklmnopqrstuvwxyzABCDEF")
        val revocations = mutableListOf<Config>()

        override suspend fun claim(invitation: Invitation, nonce: String): Config {
            if (invitation.code.first() == 'a') {
                firstRelease.await()
                return firstConfig
            }
            return secondConfig
        }

        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT
        override suspend fun activate(config: Config) = Unit
        override suspend fun command(config: Config, command: Command) = Unit
        override suspend fun revoke(config: Config) {
            revocations += config
        }
    }

    private class RecreatedPairClient : Client {
        val nonces = mutableListOf<String>()
        val config = Config("https://m5.example.test", "vp1.recreated.abcdefghijklmnopqrstuvwxyzABCD")

        override suspend fun claim(invitation: Invitation, nonce: String): Config {
            nonces += nonce
            if (nonces.size == 1) throw Failure("The first response was lost.")
            return config
        }

        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT
        override suspend fun activate(config: Config) = Unit
        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class ActivationLossPairClient : Client {
        val config = Config("https://m5.example.test", "vp1.activation.abcdefghijklmnopqrstuvwxyzABCDEFG")
        var claimCalls = 0
        var activateCalls = 0

        override suspend fun claimPending(invitation: Invitation, nonce: String): IssuedCredential {
            claimCalls += 1
            return IssuedCredential(config, Long.MAX_VALUE)
        }

        override suspend fun activate(config: Config) {
            activateCalls += 1
            if (activateCalls == 1) throw Failure("The activation response was lost.")
        }

        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT
        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class PersistedIssuedClient : Client {
        var claimCalls = 0
        var activateCalls = 0

        override suspend fun claimPending(invitation: Invitation, nonce: String): IssuedCredential {
            claimCalls += 1
            error("A persisted issued credential must not be claimed again.")
        }

        override suspend fun activate(config: Config) {
            activateCalls += 1
        }

        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT
        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class ExpiredPairClient : Client {
        val config = Config("https://m5.example.test", "vp1.expired1.abcdefghijklmnopqrstuvwxyzABCDEFG")
        var activateCalls = 0

        override suspend fun claimPending(invitation: Invitation, nonce: String): IssuedCredential =
            IssuedCredential(config, 100L)

        override suspend fun activate(config: Config) {
            activateCalls += 1
        }

        override suspend fun revoke(config: Config) = kotlinx.coroutines.awaitCancellation()
        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT
        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class UnauthorizedActivationPairClient : Client {
        val config = Config("https://m5.example.test", "vp1.unauth01.abcdefghijklmnopqrstuvwxyzABCDEFG")
        val activationFailure: Throwable = Failure("The pending credential expired.", 401)

        override suspend fun claimPending(invitation: Invitation, nonce: String): IssuedCredential =
            IssuedCredential(config, Long.MAX_VALUE)

        override suspend fun activate(config: Config) {
            throw activationFailure
        }

        override suspend fun revoke(config: Config) = kotlinx.coroutines.awaitCancellation()
        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT
        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class OrderedPairClient(
        private val trace: MutableList<String>,
    ) : Client {
        private val config = Config(
            "https://m5.example.test",
            "vp1.ordered1.abcdefghijklmnopqrstuvwxyzABCDEFG",
        )

        override suspend fun claimPending(invitation: Invitation, nonce: String): IssuedCredential {
            trace += "claim"
            return IssuedCredential(config, Long.MAX_VALUE)
        }

        override suspend fun activate(config: Config) {
            trace += "activate"
        }

        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT.also { trace += "snapshot" }
        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class RestartRevocationClient : Client {
        var revokeCalls = 0
            private set

        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT
        override suspend fun command(config: Config, command: Command) = Unit

        override suspend fun revoke(config: Config) {
            revokeCalls += 1
            if (revokeCalls == 1) kotlinx.coroutines.awaitCancellation()
        }
    }

    private class AlreadyRevokedClient : Client {
        var revokeCalls = 0

        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT
        override suspend fun command(config: Config, command: Command) = Unit

        override suspend fun revoke(config: Config) {
            revokeCalls += 1
            throw Failure("This device credential has already been revoked.", 401)
        }
    }

    private class LostVoiceClient : Client {
        val startLost = CompletableDeferred<Unit>()
        val stopKeys = mutableListOf<String>()

        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT

        override suspend fun command(config: Config, command: Command) {
            if (command == Command.VoiceStart) {
                startLost.await()
                throw Failure("The start response was lost.")
            }
        }

        override suspend fun stopVoice(config: Config, idempotencyKey: String) {
            stopKeys += idempotencyKey
        }
    }

    private class RecreatedVoiceClient(
        private val startRelease: CompletableDeferred<Unit>? = null,
        private var stopFailures: Int = 0,
        private val stopRelease: CompletableDeferred<Unit>? = null,
    ) : Client {
        val starts = mutableListOf<Config>()
        val stops = mutableListOf<VoiceStop>()
        val revocations = mutableListOf<Config>()

        override suspend fun snapshot(config: Config): Snapshot = VOICE_SNAPSHOT

        override suspend fun command(config: Config, command: Command) {
            if (command != Command.VoiceStart) return
            starts += config
            startRelease?.await()
        }

        override suspend fun stopVoice(config: Config, idempotencyKey: String) {
            stops += VoiceStop(config, idempotencyKey)
            if (stopFailures > 0) {
                stopFailures -= 1
                throw Failure("The stop response was lost.")
            }
            stopRelease?.await()
        }

        override suspend fun revoke(config: Config) {
            revocations += config
        }
    }

    private companion object {
        val DEVICE_CONFIG = Config(
            "https://m5.example.test",
            "vp1.phone123.abcdefghijklmnopqrstuvwxyzABCDEF",
        )

        val VOICE_SNAPSHOT = Snapshot(
            revision = "r_test",
            status = Status("ready", null),
            capabilities = Capabilities(voice = true, approve = true, modeCycle = true),
            desktop = Desktop(
                profile = Profile(
                    version = 2,
                    inputs = listOf(
                        Input("key_accept", Input.Kind.KEY, "Accept", "check"),
                        Input("key_voice", Input.Kind.KEY, "Voice", "mic"),
                        Input("key_mode", Input.Kind.KEY, "Mode", "cycle"),
                    ),
                    workflows = emptyList(),
                    layers = listOf(
                        Layer(
                            id = "layer-1",
                            name = "Default",
                            color = "#55D6A4",
                            bindings = mapOf(
                                "key_accept" to Binding(
                                    mapOf(Gesture.Kind.TAP to Action("approve")),
                                ),
                                "key_voice" to Binding(
                                    mapOf(Gesture.Kind.TAP to Action("voice")),
                                ),
                                "key_mode" to Binding(
                                    mapOf(Gesture.Kind.TAP to Action("mode_cycle")),
                                ),
                            ),
                        ),
                    ),
                ),
                gestures = listOf(Gesture(Gesture.Kind.TAP, "Tap")),
                choices = listOf(
                    Choice("approve", "Approve", Action("approve")),
                    Choice("voice", "Voice", Action("voice")),
                    Choice("mode", "Mode", Action("mode_cycle")),
                ),
                activeLayerId = "layer-1",
                foreground = true,
                activity = Activity.IDLE,
                agents = emptyList(),
                focusedAgentIndex = -1,
                focusedAgentId = null,
                voice = VoiceState(available = true, active = false),
                mode = Selector(false, ""),
                reasoning = Reasoning.Unavailable,
            ),
        )

        val REASONING_SNAPSHOT = VOICE_SNAPSHOT.copy(
            capabilities = VOICE_SNAPSHOT.capabilities.copy(reasoning = true),
            desktop = VOICE_SNAPSHOT.desktop?.copy(
                reasoning = Reasoning(
                    available = true,
                    label = "Medium",
                    level = Reasoning.Level.MEDIUM,
                    canIncrease = true,
                    canDecrease = true,
                    increaseTo = Reasoning.Level.HIGH,
                    decreaseTo = Reasoning.Level.LOW,
                ),
            ),
        )

        fun reasoningSnapshot(agentId: String, modelId: String): Snapshot = REASONING_SNAPSHOT.copy(
            desktop = REASONING_SNAPSHOT.desktop?.copy(
                focusedAgentId = agentId,
                model = Model(
                    available = true,
                    id = modelId,
                    label = modelId,
                    options = listOf(Model.Option(modelId, modelId, true)),
                ),
            ),
        )

        fun invitation(code: String): String =
            "vibepocket://pair?origin=https%3A%2F%2Fm5.example.test&code=$code&expiresAt=2099-01-01T00%3A05%3A00Z"
    }
}
