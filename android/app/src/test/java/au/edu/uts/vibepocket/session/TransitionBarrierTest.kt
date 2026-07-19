package au.edu.uts.vibepocket.session

import androidx.lifecycle.ViewModelStore
import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.bridge.CommandResult
import au.edu.uts.vibepocket.bridge.CommandStatus
import au.edu.uts.vibepocket.bridge.Failure
import au.edu.uts.vibepocket.connection.Claim
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.PendingCommand
import au.edu.uts.vibepocket.connection.Store
import au.edu.uts.vibepocket.connection.VoiceStop
import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Agent
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.ContextTransition
import au.edu.uts.vibepocket.control.Desktop
import au.edu.uts.vibepocket.control.Model
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
import java.io.IOException
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransitionBarrierTest {
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
    fun barrierBeginsBeforeEnqueueAndPreSuccessSnapshotCannotReleaseIt() = runTest(dispatcher) {
        val baseline = snapshot()
        val target = snapshot(focusedAgentId = AgentB)
        val store = MemoryStore()
        val client = BlockingSuccessClient(baseline, target, target)
        val session = Session(store, client, dispatcher)
        runCurrent()

        assertTrue(session.focusAgent(AgentB))
        assertTrue(session.state.value.contextTransitionPending)
        assertFalse(session.activateInput("key_accept"))
        assertFalse(session.openModel())
        assertFalse(session.startVoice("key_voice"))

        session.refresh()
        runCurrent()
        assertEquals(AgentB, session.state.value.snapshot?.desktop?.focusedAgentId)
        assertTrue(session.state.value.contextTransitionPending)
        assertNotNull(store.pendingCommand)

        client.commandRelease.complete(Unit)
        runCurrent()

        assertFalse(session.state.value.contextTransitionPending)
        assertTrue(session.state.value.inFlightIds.isEmpty())
        assertNull(store.pendingCommand)
    }

    @Test
    fun successfulDeliveryReconcilesEveryExactTarget() = runTest(dispatcher) {
        data class Case(
            val baseline: Snapshot,
            val target: Snapshot,
            val dispatch: (Session) -> Boolean,
        )

        val cases = listOf(
            Case(snapshot(Action("new_task")), snapshot(focusedAgentId = AgentB)) {
                it.activateInput(TransitionInput)
            },
            Case(snapshot(Action("attach")), snapshot()) { it.activateInput(TransitionInput) },
            Case(snapshot(), snapshot(focusedAgentId = AgentB)) { it.focusAgent(AgentB) },
            Case(snapshot(), snapshot(modelId = "model-2")) { it.selectModel("model-2") },
            Case(snapshot(), snapshot(activeLayerId = "layer-2")) { it.selectLayer("layer-2") },
        )

        cases.forEach { case ->
            val store = MemoryStore()
            val session = Session(
                store,
                ImmediateSuccessClient(case.baseline, case.target),
                dispatcher,
            )
            runCurrent()

            assertTrue(case.dispatch(session))
            assertTrue(session.state.value.contextTransitionPending)
            runCurrent()

            assertFalse(session.state.value.contextTransitionPending)
            assertNull(store.pendingCommand)
        }
    }

    @Test
    fun mappedWorkflowNewTaskAttachAndFocusBeginTheSameBarrier() = runTest(dispatcher) {
        val actions = listOf(
            Action("workflow", workflowId = "debug"),
            Action("new_task"),
            Action("attach"),
            Action("focus_next"),
            Action("focus_agent", index = 1),
        )

        actions.forEach { action ->
            val store = MemoryStore()
            val session = Session(store, DefiniteFailureClient(snapshot(action)), dispatcher)
            runCurrent()

            assertTrue(action.type, session.activateInput(TransitionInput))
            assertTrue(action.type, session.state.value.contextTransitionPending)
            runCurrent()
            assertFalse(action.type, session.state.value.contextTransitionPending)
            assertNull(store.pendingCommand)
        }
    }

    @Test
    fun definiteFailureClearsWhileUnknownAndNetworkUncertaintyStayFailClosed() = runTest(dispatcher) {
        val preDispatchStore = MemoryStore().apply {
            savePendingError = IllegalStateException("disk unavailable")
        }
        val preDispatchClient = ImmediateSuccessClient(snapshot())
        val preDispatch = Session(preDispatchStore, preDispatchClient, dispatcher)
        runCurrent()
        assertTrue(preDispatch.focusAgent(AgentB))
        runCurrent()
        assertFalse(preDispatch.state.value.contextTransitionPending)
        assertEquals(0, preDispatchClient.postCalls)

        val definiteStore = MemoryStore()
        val definite = Session(definiteStore, DefiniteFailureClient(snapshot()), dispatcher)
        runCurrent()
        assertTrue(definite.focusAgent(AgentB))
        runCurrent()
        assertFalse(definite.state.value.contextTransitionPending)
        assertNull(definiteStore.pendingCommand)

        val unknownStore = MemoryStore()
        val unknown = Session(
            unknownStore,
            OutcomeClient(
                snapshot = snapshot(),
                postError = Failure(
                    "The desktop outcome is unknown.",
                    errorCode = "command_outcome_indeterminate",
                ),
            ),
            dispatcher,
        )
        runCurrent()
        assertTrue(unknown.focusAgent(AgentB))
        runCurrent()
        assertTrue(unknown.state.value.contextTransitionPending)
        assertNotNull(unknownStore.pendingCommand)
        assertFalse(unknown.activateInput("key_accept"))

        val networkStore = MemoryStore()
        val network = Session(
            networkStore,
            OutcomeClient(
                snapshot = snapshot(),
                postError = IOException("POST response lost"),
                resultError = IOException("Bridge unreachable"),
            ),
            dispatcher,
        )
        runCurrent()
        assertTrue(network.focusAgent(AgentB))
        runCurrent()
        assertTrue(network.state.value.contextTransitionPending)
        assertNotNull(networkStore.pendingCommand)
    }

    @Test
    fun recreationRestoresBarrierAndClearsOnlyAfterRecoveredSuccessSnapshot() = runTest(dispatcher) {
        val store = MemoryStore()
        val firstClient = OutcomeClient(
            snapshot = snapshot(),
            postError = IOException("POST response lost"),
            result = CommandResult.Found(CommandStatus.RUNNING),
        )
        val first = Session(store, firstClient, dispatcher)
        val owner = ViewModelStore().apply { put("first", first) }
        runCurrent()

        assertTrue(first.focusAgent(AgentB))
        runCurrent()
        val persisted = requireNotNull(store.pendingCommand)
        assertEquals(ContextTransition.Agent(AgentB), persisted.transition)
        owner.clear()

        val recoveredClient = RecoveredClient(snapshot(focusedAgentId = AgentB))
        val recovered = Session(store, recoveredClient, dispatcher)
        assertTrue(recovered.state.value.contextTransitionPending)
        assertEquals(setOf(persisted.uiId), recovered.state.value.inFlightIds)

        runCurrent()
        assertEquals(0, recoveredClient.postCalls)
        assertEquals(listOf(persisted.operationId), recoveredClient.queries)
        assertNotNull(store.pendingCommand)
        assertTrue(recovered.state.value.contextTransitionPending)

        recoveredClient.snapshotRelease.complete(Unit)
        runCurrent()
        assertNull(store.pendingCommand)
        assertFalse(recovered.state.value.contextTransitionPending)
    }

    @Test
    fun voiceStartIsRejectedButVoiceStopRemainsAllowedDuringBarrier() = runTest(dispatcher) {
        val baseline = snapshot()
        val target = snapshot(focusedAgentId = AgentB)
        val client = BlockingSuccessClient(baseline, target, target)
        val session = Session(MemoryStore(), client, dispatcher)
        runCurrent()

        assertTrue(session.focusAgent(AgentB))
        assertFalse(session.startVoice("key_voice"))
        assertTrue(session.stopVoice("key_voice"))
        runCurrent()
        assertEquals(1, client.voiceStops)
        assertTrue(session.state.value.contextTransitionPending)

        client.commandRelease.complete(Unit)
        runCurrent()
        assertFalse(session.state.value.contextTransitionPending)
    }

    private open class SnapshotClient(private val snapshots: ArrayDeque<Snapshot>) : Client {
        override suspend fun snapshot(config: Config): Snapshot = snapshots.removeFirst()
        override suspend fun command(config: Config, command: Command) = error("Explicit operation ID required")
    }

    private class BlockingSuccessClient(vararg snapshots: Snapshot) : SnapshotClient(ArrayDeque(snapshots.toList())) {
        val commandRelease = CompletableDeferred<Unit>()
        var voiceStops = 0

        override suspend fun command(config: Config, command: Command, operationId: String) {
            commandRelease.await()
        }

        override suspend fun stopVoice(config: Config, idempotencyKey: String) {
            voiceStops += 1
        }
    }

    private class ImmediateSuccessClient(vararg snapshots: Snapshot) : SnapshotClient(ArrayDeque(snapshots.toList())) {
        var postCalls = 0

        override suspend fun command(config: Config, command: Command, operationId: String) {
            postCalls += 1
        }
    }

    private class DefiniteFailureClient(snapshot: Snapshot) : SnapshotClient(ArrayDeque(listOf(snapshot))) {
        override suspend fun command(config: Config, command: Command, operationId: String) {
            throw Failure("The command was rejected.", errorCode = "desktop_action_failed")
        }
    }

    private class OutcomeClient(
        snapshot: Snapshot,
        private val postError: Throwable,
        private val result: CommandResult? = null,
        private val resultError: Throwable? = null,
    ) : SnapshotClient(ArrayDeque(listOf(snapshot))) {
        override suspend fun command(config: Config, command: Command, operationId: String) {
            throw postError
        }

        override suspend fun commandResult(config: Config, operationId: String): CommandResult {
            resultError?.let { throw it }
            return requireNotNull(result)
        }
    }

    private class RecoveredClient(private val target: Snapshot) : Client {
        val snapshotRelease = CompletableDeferred<Unit>()
        val queries = mutableListOf<String>()
        var postCalls = 0

        override suspend fun snapshot(config: Config): Snapshot {
            snapshotRelease.await()
            return target
        }

        override suspend fun command(config: Config, command: Command) = error("Not used")

        override suspend fun command(config: Config, command: Command, operationId: String) {
            postCalls += 1
        }

        override suspend fun commandResult(config: Config, operationId: String): CommandResult {
            queries += operationId
            return CommandResult.Found(CommandStatus.SUCCEEDED)
        }
    }

    private class MemoryStore : Store {
        var config: Config? = ConfigValue
        var claim: Claim? = null
        var revocation: Config? = null
        var voiceStop: VoiceStop? = null
        var pendingCommand: PendingCommand? = null
        var savePendingError: Throwable? = null

        override fun save(config: Config) { this.config = config }
        override fun load(): Config? = config
        override fun clear() { config = null }
        override fun saveClaim(claim: Claim) { this.claim = claim }
        override fun loadClaim(): Claim? = claim
        override fun clearClaim() { claim = null }
        override fun saveRevocation(config: Config) { revocation = config }
        override fun loadRevocation(): Config? = revocation
        override fun clearRevocation() { revocation = null }
        override fun saveVoiceStop(stop: VoiceStop) { voiceStop = stop }
        override fun loadVoiceStop(): VoiceStop? = voiceStop
        override fun clearVoiceStop(idempotencyKey: String): Boolean {
            if (voiceStop?.idempotencyKey != idempotencyKey) return false
            voiceStop = null
            return true
        }
        override fun savePendingCommand(command: PendingCommand) {
            savePendingError?.let { throw it }
            pendingCommand = command
        }
        override fun loadPendingCommand(): PendingCommand? = pendingCommand
        override fun clearPendingCommand(operationId: String): Boolean {
            val current = pendingCommand ?: return true
            if (current.operationId != operationId) return false
            pendingCommand = null
            return true
        }
    }

    private fun snapshot(
        transitionAction: Action = Action("approve"),
        focusedAgentId: String = AgentA,
        modelId: String = "model-1",
        activeLayerId: String = "layer-1",
    ): Snapshot {
        val inputs = listOf(
            Input(TransitionInput, Input.Kind.KEY, "Transition", "focus"),
            Input("key_accept", Input.Kind.KEY, "Accept", "check"),
            Input("key_voice", Input.Kind.KEY, "Voice", "mic"),
        )
        val layers = listOf("layer-1", "layer-2").map { id ->
            Layer(
                id = id,
                name = id,
                color = "#FFFFFF",
                bindings = mapOf(
                    TransitionInput to Binding(mapOf(Gesture.Kind.TAP to transitionAction)),
                    "key_accept" to Binding(mapOf(Gesture.Kind.TAP to Action("approve"))),
                    "key_voice" to Binding(mapOf(Gesture.Kind.TAP to Action("voice"))),
                ),
            )
        }
        val agents = listOf(AgentA, AgentB).mapIndexed { index, id ->
            Agent(id, "Agent ${index + 1}", Activity.IDLE, id == focusedAgentId)
        }
        return Snapshot(
            revision = "r_$focusedAgentId",
            status = Status("ready", null),
            capabilities = Capabilities(
                voice = true,
                newTask = true,
                approve = true,
                focusAgent = true,
                modelPicker = true,
                model = true,
                workflow = true,
            ),
            desktop = Desktop(
                profile = Profile(3, inputs, emptyList(), layers),
                gestures = emptyList(),
                choices = emptyList(),
                activeLayerId = activeLayerId,
                foreground = true,
                activity = Activity.IDLE,
                agents = agents,
                focusedAgentIndex = agents.indexOfFirst { it.id == focusedAgentId },
                focusedAgentId = focusedAgentId,
                voice = Voice(available = true, active = false),
                mode = Selector(false, ""),
                model = Model(
                    available = true,
                    id = modelId,
                    label = modelId,
                    options = listOf(Model.Option("model-2", "Model 2", false)),
                ),
                reasoning = Reasoning.Unavailable,
            ),
        )
    }

    private companion object {
        val ConfigValue = Config("https://bridge.example.test", "0123456789abcdefghijklmn")
        const val TransitionInput = "key_transition"
        const val AgentA = "agent-aaaaaaaaaaaaaaaaaaaaaaaa"
        const val AgentB = "agent-bbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
