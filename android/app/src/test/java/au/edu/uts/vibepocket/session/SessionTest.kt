package au.edu.uts.vibepocket.session

import androidx.lifecycle.ViewModelStore
import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.bridge.Failure
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.Invitation
import au.edu.uts.vibepocket.connection.Store
import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.Desktop
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun duplicateTapIsRejectedWhileDistinctCommandsCanQueue() = runTest(dispatcher) {
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
                Command.Binding("key_accept", Gesture.Kind.TAP),
                Command.Binding("key_voice", Gesture.Kind.TAP),
            ),
            client.commands,
        )
        assertEquals(
            setOf("input:key_accept:tap", "input:key_voice:tap"),
            viewModel.state.value.inFlightIds,
        )

        client.commandRelease.complete(Unit)
        runCurrent()
        assertTrue(viewModel.state.value.inFlightIds.isEmpty())
    }

    @Test
    fun repeatableControlsQueueEveryPhysicalStep() = runTest(dispatcher) {
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
                Command.Binding("key_mode", Gesture.Kind.TAP),
                Command.Binding("key_mode", Gesture.Kind.TAP),
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
    fun transportOnlyRefreshKeepsTheVisibleControllerSnapshot() = runTest(dispatcher) {
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
        val visibleSnapshot = viewModel.state.value.snapshot

        viewModel.refresh()
        runCurrent()

        assertSame(visibleSnapshot, viewModel.state.value.snapshot)
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
    fun deliveredReasoningStepSurvivesATransientMissingSelector() = runTest(dispatcher) {
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
        assertTrue(visible?.capabilities?.reasoning == true)
        assertTrue(visible?.desktop?.reasoning?.available == true)
        assertEquals(Reasoning.Level.HIGH, visible?.desktop?.reasoning?.level)
    }

    @Test
    fun executingTaskKeepsAReasoningPredictionUntilDesktopConfirmation() = runTest(dispatcher) {
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
        assertTrue(visible?.capabilities?.reasoning == true)
        assertTrue(visible?.desktop?.reasoning?.available == true)
        assertEquals(Reasoning.Level.HIGH, visible?.desktop?.reasoning?.level)
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
    fun onClearedDrainsVoiceStopOutsideViewModelScope() = runTest(dispatcher) {
        val (viewModel, client) = voiceViewModel()
        val store = ViewModelStore().apply { put("voice", viewModel) }
        runCurrent()

        assertTrue(viewModel.startVoice("key_voice"))
        store.clear()
        runCurrent()
        assertEquals(listOf(Command.VoiceStart), client.commands)

        client.startRelease.complete(Unit)
        runCurrent()
        assertEquals(listOf(Command.VoiceStart, Command.VoiceStop), client.commands)
    }

    @Test
    fun failedPairingCanRetryWithTheSamePhoneNonce() = runTest(dispatcher) {
        val store = FakeStore(null)
        val client = RetryPairClient()
        val viewModel = Session(store = store, client = client, dispatcher = dispatcher)
        val code = "a".repeat(43)

        assertTrue(viewModel.offer("vibepocket://pair?origin=https%3A%2F%2Fm5.example.test&code=$code"))
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

    private fun voiceViewModel(): Pair<Session, BlockingVoiceClient> {
        val config = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        val client = BlockingVoiceClient()
        return Session(
            store = FakeStore(config),
            client = client,
            dispatcher = dispatcher,
        ) to client
    }

    private class FakeStore(config: Config?) : Store {
        var config: Config? = config
            private set
        var saveCalls = 0
            private set
        var clearCalls = 0
            private set

        override fun save(config: Config) {
            this.config = config
            saveCalls += 1
        }

        override fun load(): Config? = config

        override fun clear() {
            config = null
            clearCalls += 1
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

        override suspend fun command(config: Config, command: Command) = Unit
    }

    private companion object {
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
                ),
            ),
        )
    }
}
