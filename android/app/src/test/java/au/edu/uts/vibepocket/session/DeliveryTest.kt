package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.bridge.CommandResult
import au.edu.uts.vibepocket.bridge.CommandStatus
import au.edu.uts.vibepocket.bridge.Failure
import au.edu.uts.vibepocket.bridge.RemoteError
import au.edu.uts.vibepocket.connection.Claim
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.PendingCommand
import au.edu.uts.vibepocket.connection.Store
import au.edu.uts.vibepocket.connection.VoiceStop
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.Snapshot
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryTest {
    @Test
    fun processDeathBeforeWorkerStartsLeavesACompleteReplayableRecord() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = MemoryStore()
        val client = RecordingClient()
        val harness = delivery(dispatcher, client, store)
        val body = Command.UpdateWorkflowPrompt("debug", "Use exact evidence.")

        assertTrue(harness.delivery.send(body, "workflow:debug"))

        val saved = requireNotNull(store.pendingCommand)
        assertEquals(body, saved.command)
        assertEquals(ConfigValue, saved.config)
        assertEquals("workflow:debug", saved.uiId)
        UUID.fromString(saved.operationId)
        assertTrue(client.posts.isEmpty())
        assertEquals(setOf("workflow:debug"), harness.pending.snapshot())
    }

    @Test
    fun restoredNotFoundReplaysExactCommandWithSameOperationId() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val operationId = UUID.randomUUID().toString()
        val body = Command.UpdateLayerColor("layer-2", "#55D6A4")
        val restored = PendingCommand(ConfigValue, body, operationId, "layer:2")
        val store = MemoryStore().apply { pendingCommand = restored }
        val client = ResultClient(CommandResult.NotFound)
        val harness = delivery(dispatcher, client, store)

        runCurrent()

        assertEquals(listOf(Query(ConfigValue, operationId)), client.queries)
        assertEquals(listOf(Post(ConfigValue, body, operationId)), client.posts)
        assertEquals(listOf(restored), harness.accepted)
        assertNull(store.pendingCommand)
        assertTrue(harness.pending.snapshot().isEmpty())
    }

    @Test
    fun terminalUnknownClearsOutboxAndAllowsNextCommand() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = MemoryStore()
        val client = UnknownThenSuccessClient()
        val harness = delivery(dispatcher, client, store)

        assertTrue(harness.delivery.send(Command.Approve, "approve"))
        runCurrent()

        assertNull(store.pendingCommand)
        assertTrue(harness.pending.snapshot().isEmpty())
        val failure = harness.rejected.single().error as Failure
        assertEquals(CommandOutcomeIndeterminate, failure.errorCode)
        assertEquals(AmbiguousOutcome, failure.message)

        assertTrue(harness.delivery.send(Command.Reject, "reject"))
        runCurrent()

        assertEquals(listOf(Command.Approve, Command.Reject), client.posts.map(Post::command))
        assertEquals(Command.Reject, harness.accepted.single().command)
        assertNull(store.pendingCommand)
    }

    @Test
    fun directIndeterminateFailureIsDurablySettled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = MemoryStore()
        val client = IndeterminateClient()
        val harness = delivery(dispatcher, client, store)

        assertTrue(harness.delivery.send(Command.Stop, "stop"))
        runCurrent()

        assertNull(store.pendingCommand)
        assertTrue(harness.pending.snapshot().isEmpty())
        val failure = harness.rejected.single().error as Failure
        assertEquals(CommandOutcomeIndeterminate, failure.errorCode)
        assertEquals(AmbiguousOutcome, failure.message)
    }

    @Test
    fun persistenceFailureReturnsFalseAndPostsNothing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = MemoryStore().apply {
            savePendingFailure = IllegalStateException("disk unavailable")
        }
        val client = RecordingClient()
        val harness = delivery(dispatcher, client, store)

        assertFalse(harness.delivery.send(Command.Approve, "approve"))
        runCurrent()

        assertTrue(client.posts.isEmpty())
        assertEquals("disk unavailable", harness.rejected.single().error.message)
        assertNull(store.pendingCommand)
        assertTrue(harness.pending.snapshot().isEmpty())
    }

    @Test
    fun distinctRapidSendsQueueWhileDuplicateUiIdIsRejected() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = MemoryStore()
        val client = GateClient()
        val harness = delivery(dispatcher, client, store)

        assertTrue(harness.delivery.send(Command.Approve, "approve"))
        assertFalse(harness.delivery.send(Command.Approve, "approve"))
        assertTrue(harness.delivery.send(Command.Reject, "reject"))
        assertEquals("approve", store.pendingCommand?.uiId)
        assertEquals(listOf("approve", "reject"), store.pendingCommands.map(PendingCommand::uiId))
        assertEquals(setOf("approve", "reject"), harness.pending.snapshot())
        runCurrent()
        assertEquals(listOf(Command.Approve), client.posts.map(Post::command))

        client.release.complete(Unit)
        runCurrent()
        assertEquals(listOf(Command.Approve, Command.Reject), client.posts.map(Post::command))
        assertNull(store.pendingCommand)
        assertTrue(harness.pending.snapshot().isEmpty())
    }

    @Test
    fun runningOperationRemainsRecoverableWithoutReplay() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = MemoryStore()
        val client = TimeoutClient(CommandResult.Found(CommandStatus.RUNNING))
        val harness = delivery(dispatcher, client, store)

        assertTrue(harness.delivery.send(Command.Approve, "approve"))
        runCurrent()

        val retained = requireNotNull(store.pendingCommand)
        assertEquals(1, client.posts.size)
        assertEquals(1, client.queries.size)
        assertEquals(1, harness.unconfirmed.size)

        client.result = CommandResult.Found(CommandStatus.SUCCEEDED)
        harness.delivery.recover()
        runCurrent()

        assertEquals(1, client.posts.size)
        assertEquals(listOf(retained.operationId, retained.operationId), client.queries.map(Query::operationId))
        assertEquals(listOf(retained), harness.accepted)
        assertNull(store.pendingCommand)
    }

    @Test
    fun restoredCommandWaitsForItsExactConfigurationOwner() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val operationId = UUID.randomUUID().toString()
        val restored = PendingCommand(OtherConfig, Command.Approve, operationId, "approve")
        val store = MemoryStore().apply { pendingCommand = restored }
        val client = ResultClient(CommandResult.NotFound)
        val harness = delivery(dispatcher, client, store, bind = ConfigValue)

        runCurrent()
        assertTrue(client.queries.isEmpty())
        assertTrue(client.posts.isEmpty())

        harness.delivery.bind(OtherConfig)
        runCurrent()

        assertEquals(listOf(Query(OtherConfig, operationId)), client.queries)
        assertEquals(listOf(Post(OtherConfig, Command.Approve, operationId)), client.posts)
    }

    private fun kotlinx.coroutines.test.TestScope.delivery(
        dispatcher: CoroutineDispatcher,
        client: Client,
        store: MemoryStore = MemoryStore(),
        bind: Config = ConfigValue,
    ): Harness {
        val pending = Pending()
        val accepted = mutableListOf<PendingCommand>()
        val rejected = mutableListOf<Rejection>()
        val unconfirmed = mutableListOf<Throwable?>()
        val delivery = Delivery(
            scope = this,
            dispatcher = dispatcher,
            client = client,
            store = store,
            restored = store.loadPendingCommandsRecord(),
            pending = pending,
            publishPending = {},
            accepted = accepted::add,
            rejected = { config, id, command, error ->
                rejected += Rejection(config, id, command, error)
            },
            unconfirmed = { _, error -> unconfirmed += error },
        ).also { it.bind(bind) }
        return Harness(delivery, pending, accepted, rejected, unconfirmed)
    }

    private data class Harness(
        val delivery: Delivery,
        val pending: Pending,
        val accepted: List<PendingCommand>,
        val rejected: List<Rejection>,
        val unconfirmed: List<Throwable?>,
    )

    private data class Rejection(
        val config: Config,
        val uiId: String,
        val command: PendingCommand?,
        val error: Throwable,
    )

    private data class Post(
        val config: Config,
        val command: Command,
        val operationId: String,
    )

    private data class Query(
        val config: Config,
        val operationId: String,
    )

    private open class RecordingClient : Client {
        val posts = mutableListOf<Post>()

        override suspend fun snapshot(config: Config): Snapshot = error("Not used")
        override suspend fun command(config: Config, command: Command) = error("Explicit operation ID required")

        override suspend fun command(config: Config, command: Command, operationId: String) {
            posts += Post(config, command, operationId)
        }
    }

    private class GateClient : RecordingClient() {
        val release = CompletableDeferred<Unit>()

        override suspend fun command(config: Config, command: Command, operationId: String) {
            super.command(config, command, operationId)
            release.await()
        }
    }

    private class ResultClient(private val result: CommandResult) : RecordingClient() {
        val queries = mutableListOf<Query>()

        override suspend fun commandResult(config: Config, operationId: String): CommandResult {
            queries += Query(config, operationId)
            return result
        }
    }

    private class UnknownThenSuccessClient : RecordingClient() {
        val queries = mutableListOf<Query>()

        override suspend fun command(config: Config, command: Command, operationId: String) {
            super.command(config, command, operationId)
            if (posts.size == 1) throw IOException("response lost")
        }

        override suspend fun commandResult(config: Config, operationId: String): CommandResult {
            queries += Query(config, operationId)
            return CommandResult.Found(
                CommandStatus.UNKNOWN,
                RemoteError("result_evicted", "Result history expired."),
            )
        }
    }

    private class IndeterminateClient : RecordingClient() {
        override suspend fun command(config: Config, command: Command, operationId: String) {
            super.command(config, command, operationId)
            throw Failure("The desktop outcome is unknown.", errorCode = CommandOutcomeIndeterminate)
        }
    }

    private class TimeoutClient(var result: CommandResult) : RecordingClient() {
        val queries = mutableListOf<Query>()

        override suspend fun command(config: Config, command: Command, operationId: String) {
            super.command(config, command, operationId)
            throw IOException("response lost")
        }

        override suspend fun commandResult(config: Config, operationId: String): CommandResult {
            queries += Query(config, operationId)
            return result
        }
    }

    private class MemoryStore : Store {
        var config: Config? = ConfigValue
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
        var savePendingFailure: Throwable? = null

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
            val current = voiceStop ?: return true
            if (current.idempotencyKey != idempotencyKey) return false
            voiceStop = null
            return true
        }

        override fun savePendingCommand(command: PendingCommand) {
            savePendingFailure?.let { throw it }
            pendingCommands += command
        }

        override fun loadPendingCommand(): PendingCommand? = pendingCommand
        override fun loadPendingCommands(): List<PendingCommand> = pendingCommands.toList()

        override fun clearPendingCommand(operationId: String): Boolean {
            val current = pendingCommand ?: return true
            if (current.operationId != operationId) return false
            pendingCommands.removeAt(0)
            return true
        }
    }

    private companion object {
        val ConfigValue = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        val OtherConfig = Config("https://other.example.test", "abcdefghijklmnopqrstuvwxyz")
    }
}
