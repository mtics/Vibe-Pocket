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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryTest {
    @Test
    fun distinctCommandsCannotArriveInReverseHttpOrder() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = GateClient()
        val harness = delivery(dispatcher, client)

        assertTrue(harness.delivery.send(Command.Approve, "approve"))
        assertTrue(harness.delivery.send(Command.Reject, "reject"))
        runCurrent()
        assertEquals(listOf(Command.Approve), client.commands)

        client.first.release.complete(Unit)
        runCurrent()
        assertEquals(listOf(Command.Approve, Command.Reject), client.commands)
        assertEquals(2, client.operationIds.distinct().size)
    }

    @Test
    fun lastSelectionWinsWithinAReversibleQueueSegment() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = GateClient()
        val harness = delivery(dispatcher, client)

        harness.delivery.send(Command.Approve, "approve")
        runCurrent()
        harness.delivery.send(Command.SelectLayer("layer-1"), "layer:1", "layer")
        harness.delivery.send(Command.SelectModel("model-1"), "model:1", "model")
        harness.delivery.send(Command.SelectLayer("layer-2"), "layer:2", "layer")

        client.first.release.complete(Unit)
        runCurrent()

        assertEquals(
            listOf(
                Command.Approve,
                Command.SelectModel("model-1"),
                Command.SelectLayer("layer-2"),
            ),
            client.commands,
        )
    }

    @Test
    fun saveFailureDispatchesNothing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = MemoryStore().apply { savePendingFailure = IllegalStateException("disk unavailable") }
        val client = RecordingClient()
        val harness = delivery(dispatcher, client, store)

        assertTrue(harness.delivery.send(Command.Approve, "approve"))
        runCurrent()

        assertTrue(client.commands.isEmpty())
        assertEquals("disk unavailable", harness.rejected.single().message)
        assertTrue(harness.pending.snapshot().isEmpty())
    }

    @Test
    fun postTimeoutThenSucceededClearsOutboxWithoutReplay() = runTest {
        val result = CommandResult.Found(CommandStatus.SUCCEEDED)
        val fixture = timeoutFixture(result)

        assertTrue(fixture.harness.delivery.send(Command.Approve, "approve"))
        runCurrent()

        assertEquals(1, fixture.client.commandCalls)
        assertEquals(1, fixture.client.queryCalls)
        assertEquals(1, fixture.harness.accepted)
        assertNull(fixture.store.pendingCommand)
        assertTrue(fixture.harness.pending.snapshot().isEmpty())
    }

    @Test
    fun postTimeoutThenUnknownIsTerminalAndPreservesErrorCode() = runTest {
        val result = CommandResult.Found(
            CommandStatus.UNKNOWN,
            RemoteError("result_evicted", "Result history expired."),
        )
        val fixture = timeoutFixture(result)

        fixture.harness.delivery.send(Command.Approve, "approve")
        runCurrent()

        assertEquals(1, fixture.client.commandCalls)
        assertNull(fixture.store.pendingCommand)
        val failure = fixture.harness.rejected.single() as Failure
        assertEquals("result_evicted", failure.errorCode)
        assertEquals("Result history expired.", failure.message)
    }

    @Test
    fun postTimeoutThenRunningRetainsOutboxAndBlocksNextCommand() = runTest {
        val fixture = timeoutFixture(CommandResult.Found(CommandStatus.RUNNING))

        fixture.harness.delivery.send(Command.Approve, "approve")
        fixture.harness.delivery.send(Command.Reject, "reject")
        runCurrent()

        assertEquals(1, fixture.client.commandCalls)
        assertEquals(1, fixture.harness.unconfirmed.size)
        assertEquals("approve", fixture.store.pendingCommand?.uiId)
        assertEquals(setOf("approve"), fixture.harness.pending.snapshot())
        assertFalse(fixture.harness.delivery.send(Command.Reject, "reject"))
    }

    @Test
    fun connectionRecoveryRequeriesRetainedOperationWithoutPostReplay() = runTest {
        val fixture = timeoutFixture(CommandResult.Found(CommandStatus.RUNNING))

        fixture.harness.delivery.send(Command.Approve, "approve")
        runCurrent()
        fixture.client.result = CommandResult.Found(CommandStatus.SUCCEEDED)

        fixture.harness.delivery.recover()
        runCurrent()

        assertEquals(1, fixture.client.commandCalls)
        assertEquals(2, fixture.client.queryCalls)
        assertEquals(1, fixture.harness.accepted)
        assertNull(fixture.store.pendingCommand)
    }

    @Test
    fun postTimeoutThenUnreachableQueryRetainsOutboxWithoutReplay() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = MemoryStore()
        val client = UnreachableResultClient()
        val harness = delivery(dispatcher, client, store)

        harness.delivery.send(Command.Approve, "approve")
        runCurrent()

        assertEquals(1, client.commandCalls)
        assertEquals(1, client.queryCalls)
        assertEquals("approve", store.pendingCommand?.uiId)
        assertEquals(1, harness.unconfirmed.size)
        assertFalse(harness.delivery.send(Command.Reject, "reject"))
    }

    @Test
    fun postTimeoutThenNotFoundIsTerminalAndNeverReplays() = runTest {
        val fixture = timeoutFixture(CommandResult.NotFound)

        fixture.harness.delivery.send(Command.Approve, "approve")
        runCurrent()

        assertEquals(1, fixture.client.commandCalls)
        assertEquals(1, fixture.client.queryCalls)
        assertEquals("The Bridge did not receive this command.", fixture.harness.rejected.single().message)
        assertNull(fixture.store.pendingCommand)
    }

    @Test
    fun restoredOutboxIsVisibleAndQueriedWithoutPostReplay() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restored = PendingCommand(ConfigValue, UUID.randomUUID().toString(), "approve")
        val store = MemoryStore().apply { pendingCommand = restored }
        val client = TimeoutClient(CommandResult.Found(CommandStatus.SUCCEEDED), postFails = false)
        val harness = delivery(dispatcher, client, store)

        assertEquals(setOf("approve"), harness.pending.snapshot())
        runCurrent()

        assertEquals(0, client.commandCalls)
        assertEquals(1, client.queryCalls)
        assertNull(store.pendingCommand)
    }

    @Test
    fun separateUiClicksReceiveSeparateOperationIds() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = RecordingClient()
        val harness = delivery(dispatcher, client)

        assertTrue(harness.delivery.send(Command.Approve, "approve"))
        runCurrent()
        assertTrue(harness.delivery.send(Command.Approve, "approve"))
        runCurrent()

        assertEquals(2, client.operationIds.size)
        assertNotEquals(client.operationIds[0], client.operationIds[1])
        client.operationIds.forEach { UUID.fromString(it) }
    }

    private fun kotlinx.coroutines.test.TestScope.timeoutFixture(
        result: CommandResult,
    ): TimeoutFixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = MemoryStore()
        val client = TimeoutClient(result)
        return TimeoutFixture(delivery(dispatcher, client, store), client, store)
    }

    private fun kotlinx.coroutines.test.TestScope.delivery(
        dispatcher: CoroutineDispatcher,
        client: Client,
        store: MemoryStore = MemoryStore(),
    ): Harness {
        val pending = Pending()
        val rejected = mutableListOf<Throwable>()
        val unconfirmed = mutableListOf<Throwable?>()
        var accepted = 0
        val delivery = Delivery(
            scope = this,
            dispatcher = dispatcher,
            client = client,
            store = store,
            restored = store.loadPendingCommandRecord(),
            pending = pending,
            publishPending = {},
            accepted = { accepted += 1 },
            rejected = { _, error -> rejected += error },
            unconfirmed = { _, error -> unconfirmed += error },
        ).also { it.bind(ConfigValue) }
        return Harness(delivery, pending, rejected, unconfirmed) { accepted }
    }

    private data class TimeoutFixture(
        val harness: Harness,
        val client: TimeoutClient,
        val store: MemoryStore,
    )

    private class Harness(
        val delivery: Delivery,
        val pending: Pending,
        val rejected: List<Throwable>,
        val unconfirmed: List<Throwable?>,
        private val acceptedCount: () -> Int,
    ) {
        val accepted: Int get() = acceptedCount()
    }

    private open class RecordingClient : Client {
        val commands = mutableListOf<Command>()
        val operationIds = mutableListOf<String>()

        override suspend fun snapshot(config: Config): Snapshot = error("Not used")
        override suspend fun command(config: Config, command: Command) = error("Explicit operation ID required")

        override suspend fun command(config: Config, command: Command, operationId: String) {
            commands += command
            operationIds += operationId
        }
    }

    private class GateClient : RecordingClient() {
        data class First(val release: CompletableDeferred<Unit> = CompletableDeferred())

        val first = First()

        override suspend fun command(config: Config, command: Command, operationId: String) {
            super.command(config, command, operationId)
            if (commands.size == 1) first.release.await()
        }
    }

    private class TimeoutClient(
        var result: CommandResult,
        private val postFails: Boolean = true,
    ) : Client {
        var commandCalls = 0
        var queryCalls = 0

        override suspend fun snapshot(config: Config): Snapshot = error("Not used")
        override suspend fun command(config: Config, command: Command) = error("Explicit operation ID required")

        override suspend fun command(config: Config, command: Command, operationId: String) {
            commandCalls += 1
            if (postFails) throw IOException("response lost")
        }

        override suspend fun commandResult(config: Config, operationId: String): CommandResult {
            queryCalls += 1
            return result
        }
    }

    private class UnreachableResultClient : Client {
        var commandCalls = 0
        var queryCalls = 0

        override suspend fun snapshot(config: Config): Snapshot = error("Not used")
        override suspend fun command(config: Config, command: Command) = error("Explicit operation ID required")

        override suspend fun command(config: Config, command: Command, operationId: String) {
            commandCalls += 1
            throw IOException("POST response lost")
        }

        override suspend fun commandResult(config: Config, operationId: String): CommandResult {
            queryCalls += 1
            throw IOException("Bridge unreachable")
        }
    }

    private class MemoryStore : Store {
        var config: Config? = ConfigValue
        var claim: Claim? = null
        var revocation: Config? = null
        var voiceStop: VoiceStop? = null
        var pendingCommand: PendingCommand? = null
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

    private companion object {
        val ConfigValue = Config("https://m5.example.test", "0123456789abcdefghijklmn")
    }
}
