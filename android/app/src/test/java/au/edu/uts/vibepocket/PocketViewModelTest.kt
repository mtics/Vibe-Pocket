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
import org.junit.Assert.assertNull
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
    fun rapidSecondTapIsRejectedWhileFirstCommandIsInFlight() = runTest(dispatcher) {
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
        runCurrent()
        assertEquals(listOf(PocketCommand.Binding("key_accept", ControllerGesture.TAP)), client.commands)

        client.commandRelease.complete(Unit)
        runCurrent()
        assertNull(viewModel.state.value.inFlightId)
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

    private companion object {
        val VOICE_SNAPSHOT = PocketSnapshot(
            revision = "r_test",
            status = BridgeStatus("ready", null),
            controls = DesktopControls(voice = true, approve = true),
            controller = ControllerState(
                profile = ControllerProfile(
                    version = 2,
                    inputs = listOf(
                        ControllerInput("key_accept", InputKind.KEY, "Accept", "check"),
                        ControllerInput("key_voice", InputKind.KEY, "Voice", "mic"),
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
                            ),
                        ),
                    ),
                ),
                gestures = listOf(GestureOption(ControllerGesture.TAP, "Tap")),
                actionCatalog = listOf(
                    ActionCatalogEntry("approve", "Approve", ControllerAction("approve")),
                    ActionCatalogEntry("voice", "Voice", ControllerAction("voice")),
                ),
                activeLayerId = "layer-1",
                taskState = TaskState.IDLE,
                agents = emptyList(),
                focusedAgentIndex = -1,
                focusedAgentId = null,
                voice = VoiceStatus(available = true, active = false),
                mode = SelectorStatus(false, ""),
                reasoning = SelectorStatus(false, ""),
            ),
        )
    }
}
