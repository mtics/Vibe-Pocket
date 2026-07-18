package au.edu.uts.vibepocket

import androidx.lifecycle.ViewModelStore
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
class PocketViewModelTest {
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
        val config = ConnectionConfig("https://m5.example.test", "0123456789abcdefghijklmn")
        val client = BlockingClient()
        val viewModel = PocketViewModel(
            store = FakeStore(config),
            client = client,
            ioDispatcher = dispatcher,
        )
        runCurrent()

        assertTrue(viewModel.activateInput("key_accept", ControllerGesture.TAP))
        assertFalse(viewModel.activateInput("key_accept", ControllerGesture.TAP))
        assertTrue(viewModel.activateInput("key_voice", ControllerGesture.TAP))
        runCurrent()
        assertEquals(
            listOf(
                PocketCommand.Binding("key_accept", ControllerGesture.TAP),
                PocketCommand.Binding("key_voice", ControllerGesture.TAP),
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
        val config = ConnectionConfig("https://m5.example.test", "0123456789abcdefghijklmn")
        val client = BlockingClient()
        val viewModel = PocketViewModel(
            store = FakeStore(config),
            client = client,
            ioDispatcher = dispatcher,
        )
        runCurrent()

        assertTrue(viewModel.activateInput("key_mode"))
        assertTrue(viewModel.activateInput("key_mode"))
        runCurrent()

        assertEquals(
            listOf(
                PocketCommand.Binding("key_mode", ControllerGesture.TAP),
                PocketCommand.Binding("key_mode", ControllerGesture.TAP),
            ),
            client.commands,
        )
        assertEquals(2, viewModel.state.value.inFlightIds.size)
    }

    @Test
    fun backgroundRefreshKeepsAnAlreadyLoadedControllerInteractive() = runTest(dispatcher) {
        val config = ConnectionConfig("https://m5.example.test", "0123456789abcdefghijklmn")
        val client = BlockingRefreshClient()
        val viewModel = PocketViewModel(
            store = FakeStore(config),
            client = client,
            ioDispatcher = dispatcher,
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
            status = BridgeStatus("ready", "Adjusted the visible ChatGPT Codex control."),
        )
        val client = SnapshotQueueClient(VOICE_SNAPSHOT, transportOnlyUpdate)
        val viewModel = PocketViewModel(
            store = FakeStore(ConnectionConfig("https://m5.example.test", "0123456789abcdefghijklmn")),
            client = client,
            ioDispatcher = dispatcher,
        )
        runCurrent()
        val visibleSnapshot = viewModel.state.value.snapshot

        viewModel.refresh()
        runCurrent()

        assertSame(visibleSnapshot, viewModel.state.value.snapshot)
    }

    @Test
    fun rapidVoiceReleaseQueuesStopBehindStart() = runTest(dispatcher) {
        val config = ConnectionConfig("https://m5.example.test", "0123456789abcdefghijklmn")
        val client = BlockingVoiceClient()
        val viewModel = PocketViewModel(
            store = FakeStore(config),
            client = client,
            ioDispatcher = dispatcher,
        )
        runCurrent()

        assertTrue(viewModel.startVoice("key_voice"))
        assertTrue(viewModel.stopVoice("key_voice"))
        runCurrent()
        assertEquals(listOf(PocketCommand.VoiceStart), client.commands)

        client.startRelease.complete(Unit)
        runCurrent()
        assertEquals(listOf(PocketCommand.VoiceStart, PocketCommand.VoiceStop), client.commands)
    }

    @Test
    fun lifecycleStopQueuesVoiceStopForCurrentOwner() = runTest(dispatcher) {
        val (viewModel, client) = voiceViewModel()
        runCurrent()

        assertTrue(viewModel.startVoice("key_voice"))
        viewModel.setForeground(false)
        runCurrent()
        assertEquals(listOf(PocketCommand.VoiceStart), client.commands)

        client.startRelease.complete(Unit)
        runCurrent()
        assertEquals(listOf(PocketCommand.VoiceStart, PocketCommand.VoiceStop), client.commands)
    }

    @Test
    fun disconnectQueuesVoiceStopForCurrentOwner() = runTest(dispatcher) {
        val (viewModel, client) = voiceViewModel()
        runCurrent()

        assertTrue(viewModel.startVoice("key_voice"))
        viewModel.disconnect()
        runCurrent()
        assertEquals(listOf(PocketCommand.VoiceStart), client.commands)

        client.startRelease.complete(Unit)
        runCurrent()
        assertEquals(listOf(PocketCommand.VoiceStart, PocketCommand.VoiceStop), client.commands)
    }

    @Test
    fun onClearedDrainsVoiceStopOutsideViewModelScope() = runTest(dispatcher) {
        val (viewModel, client) = voiceViewModel()
        val store = ViewModelStore().apply { put("voice", viewModel) }
        runCurrent()

        assertTrue(viewModel.startVoice("key_voice"))
        store.clear()
        runCurrent()
        assertEquals(listOf(PocketCommand.VoiceStart), client.commands)

        client.startRelease.complete(Unit)
        runCurrent()
        assertEquals(listOf(PocketCommand.VoiceStart, PocketCommand.VoiceStop), client.commands)
    }

    private fun voiceViewModel(): Pair<PocketViewModel, BlockingVoiceClient> {
        val config = ConnectionConfig("https://m5.example.test", "0123456789abcdefghijklmn")
        val client = BlockingVoiceClient()
        return PocketViewModel(
            store = FakeStore(config),
            client = client,
            ioDispatcher = dispatcher,
        ) to client
    }

    private class FakeStore(private var config: ConnectionConfig?) : ConfigStore {
        override fun save(config: ConnectionConfig) {
            this.config = config
        }

        override fun load(): ConnectionConfig? = config

        override fun clear() {
            config = null
        }
    }

    private class BlockingClient : PocketClient {
        val commands = mutableListOf<PocketCommand>()
        val commandRelease = CompletableDeferred<Unit>()

        override suspend fun snapshot(config: ConnectionConfig): PocketSnapshot = VOICE_SNAPSHOT

        override suspend fun command(config: ConnectionConfig, command: PocketCommand) {
            commands += command
            commandRelease.await()
        }
    }

    private class BlockingVoiceClient : PocketClient {
        val commands = mutableListOf<PocketCommand>()
        val startRelease = CompletableDeferred<Unit>()

        override suspend fun snapshot(config: ConnectionConfig): PocketSnapshot = VOICE_SNAPSHOT

        override suspend fun command(config: ConnectionConfig, command: PocketCommand) {
            commands += command
            if (command == PocketCommand.VoiceStart) startRelease.await()
        }
    }

    private class BlockingRefreshClient : PocketClient {
        var snapshotCalls = 0
        val refreshRelease = CompletableDeferred<Unit>()

        override suspend fun snapshot(config: ConnectionConfig): PocketSnapshot {
            snapshotCalls += 1
            if (snapshotCalls > 1) refreshRelease.await()
            return VOICE_SNAPSHOT
        }

        override suspend fun command(config: ConnectionConfig, command: PocketCommand) = Unit
    }

    private class SnapshotQueueClient(
        vararg snapshots: PocketSnapshot,
    ) : PocketClient {
        private val queuedSnapshots = ArrayDeque(snapshots.toList())

        override suspend fun snapshot(config: ConnectionConfig): PocketSnapshot = queuedSnapshots.removeFirst()

        override suspend fun command(config: ConnectionConfig, command: PocketCommand) = Unit
    }

    private companion object {
        val VOICE_SNAPSHOT = PocketSnapshot(
            revision = "r_test",
            status = BridgeStatus("ready", null),
            controls = DesktopControls(voice = true, approve = true, modeCycle = true),
            controller = ControllerState(
                profile = ControllerProfile(
                    version = 2,
                    inputs = listOf(
                        ControllerInput("key_accept", InputKind.KEY, "Accept", "check"),
                        ControllerInput("key_voice", InputKind.KEY, "Voice", "mic"),
                        ControllerInput("key_mode", InputKind.KEY, "Mode", "cycle"),
                    ),
                    workflows = emptyList(),
                    layers = listOf(
                        ControllerLayer(
                            id = "layer-1",
                            name = "Default",
                            color = "#55D6A4",
                            bindings = mapOf(
                                "key_accept" to BindingDescriptor(
                                    mapOf(ControllerGesture.TAP to ControllerAction("approve")),
                                ),
                                "key_voice" to BindingDescriptor(
                                    mapOf(ControllerGesture.TAP to ControllerAction("voice")),
                                ),
                                "key_mode" to BindingDescriptor(
                                    mapOf(ControllerGesture.TAP to ControllerAction("mode_cycle")),
                                ),
                            ),
                        ),
                    ),
                ),
                gestures = listOf(GestureOption(ControllerGesture.TAP, "Tap")),
                actionCatalog = listOf(
                    ActionCatalogEntry("approve", "Approve", ControllerAction("approve")),
                    ActionCatalogEntry("voice", "Voice", ControllerAction("voice")),
                    ActionCatalogEntry("mode", "Mode", ControllerAction("mode_cycle")),
                ),
                activeLayerId = "layer-1",
                desktopFocused = true,
                taskState = TaskState.IDLE,
                agents = emptyList(),
                focusedAgentIndex = -1,
                focusedAgentId = null,
                voice = VoiceStatus(available = true, active = false),
                mode = SelectorStatus(false, ""),
                reasoning = ReasoningStatus.Unavailable,
            ),
        )
    }
}
