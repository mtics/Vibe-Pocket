package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.bridge.IssuedCredential
import au.edu.uts.vibepocket.connection.Claim
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.Invitation
import au.edu.uts.vibepocket.connection.PendingCommand
import au.edu.uts.vibepocket.connection.Store
import au.edu.uts.vibepocket.connection.VoiceStop
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionTest {
    @Test
    fun disconnectCannotBeOvertakenByConnectionPublication() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            runBlocking {
                val visible = AtomicReference<Config?>(Original)
                val store = MemoryStore(Original)
                val connectedEntered = CountDownLatch(1)
                val releaseConnected = CountDownLatch(1)
                val disconnectFinished = CountDownLatch(1)
                val errors = mutableListOf<Throwable>()
                val connection = Connection(
                    scope = this,
                    dispatcher = dispatcher,
                    client = StaticClient(),
                    store = store,
                    pending = Pending(),
                    current = visible::get,
                    publishPending = {},
                    connected = { config, _ ->
                        connectedEntered.countDown()
                        check(releaseConnected.await(2, TimeUnit.SECONDS))
                        visible.set(config)
                    },
                    disconnected = { visible.set(null) },
                    rejected = errors::add,
                    recover = {},
                )

                assertTrue(connection.connect(Replacement.baseUrl, Replacement.credential))
                assertTrue(connectedEntered.await(2, TimeUnit.SECONDS))

                val disconnect = thread(name = "connection-disconnect") {
                    connection.disconnect()
                    disconnectFinished.countDown()
                }
                assertFalse(disconnectFinished.await(100, TimeUnit.MILLISECONDS))

                releaseConnected.countDown()
                assertTrue(disconnectFinished.await(2, TimeUnit.SECONDS))
                disconnect.join(2_000)

                assertEquals(null, visible.get())
                assertEquals(null, store.load())
                assertTrue(errors.isEmpty())
            }
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun unreachableRevocationFromAnotherOriginDoesNotBlockConnection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = MemoryStore(null, listOf(RevokedA))
        val client = BlockingRevocationClient()
        val harness = Harness(backgroundScope, dispatcher, store, client)

        harness.connection.restore()
        runCurrent()
        assertEquals(listOf(RevokedA), client.revocationAttempts)

        assertTrue(harness.connection.connect(ConnectedB.baseUrl, ConnectedB.credential))
        runCurrent()

        assertEquals(ConnectedB.normalizedUrl, harness.visible?.normalizedUrl)
        assertEquals(ConnectedB.credential, harness.visible?.credential)
        assertEquals(listOf(RevokedA), store.queuedRevocations)
        assertTrue(harness.errors.isEmpty())
    }

    @Test
    fun pendingRevocationBlocksConnectAndPairForTheSameNormalizedOrigin() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = MemoryStore(null, listOf(RevokedA))
        val client = BlockingRevocationClient()
        val harness = Harness(backgroundScope, dispatcher, store, client)

        harness.connection.restore()
        runCurrent()

        assertFalse(
            harness.connection.connect(
                "HTTPS://BRIDGE-A.EXAMPLE.TEST/",
                "vp1.replaced.abcdefghijklmnopqrstuvwxyzABCDEFG",
            ),
        )
        assertFalse(
            harness.connection.pair(
                Invitation(
                    "HTTPS://BRIDGE-A.EXAMPLE.TEST/",
                    "a".repeat(43),
                    Long.MAX_VALUE,
                ),
            ),
        )
        runCurrent()

        assertEquals(0, client.snapshotCalls)
        assertTrue(client.claimedInvitations.isEmpty())
        assertEquals(listOf(RevokedA), store.queuedRevocations)
        assertEquals(2, harness.errors.size)
        assertTrue(harness.errors.all { it is IllegalStateException })
    }

    @Test
    fun pendingRevocationFromAnotherOriginDoesNotBlockPairing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = MemoryStore(null, listOf(RevokedA))
        val client = BlockingRevocationClient()
        val harness = Harness(backgroundScope, dispatcher, store, client)
        val invitation = Invitation(
            "HTTPS://BRIDGE-B.EXAMPLE.TEST/",
            "b".repeat(43),
            Long.MAX_VALUE,
        )

        harness.connection.restore()
        runCurrent()
        assertTrue(harness.connection.pair(invitation))
        runCurrent()

        assertEquals(listOf(invitation), client.claimedInvitations)
        assertEquals(ConnectedB.normalizedUrl, harness.visible?.normalizedUrl)
        assertEquals(null, store.loadClaim())
        assertEquals(listOf(RevokedA), store.queuedRevocations)
        assertTrue(harness.errors.isEmpty())
    }

    @Test
    fun pendingRegistrationFailureRollsBackAttemptOwnership() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pending = Pending().also { assertTrue(it.add("connection:1")) }
        val client = StaticClient()
        val publications = mutableListOf<Set<String>>()
        val harness = Harness(
            backgroundScope,
            dispatcher,
            MemoryStore(null),
            client,
            pending = pending,
            publishPending = { publications += pending.snapshot() },
        )

        assertFalse(harness.connection.connect(ConnectedB.baseUrl, ConnectedB.credential))
        assertEquals(setOf("connection:1"), pending.snapshot())
        pending.remove("connection:1")

        assertTrue(harness.connection.connect(ConnectedB.baseUrl, ConnectedB.credential))
        assertEquals(listOf(setOf("connection:1")), publications)
        runCurrent()

        assertEquals(1, client.snapshotCalls)
        assertEquals(ConnectedB, harness.visible)
        assertTrue(pending.snapshot().isEmpty())
        assertTrue(harness.errors.isEmpty())
    }

    @Test
    fun pendingPublicationFailureRollsBackAttemptOwnership() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pending = Pending()
        val client = StaticClient()
        val publications = mutableListOf<Set<String>>()
        var failPublication = true
        val harness = Harness(
            backgroundScope,
            dispatcher,
            MemoryStore(null),
            client,
            pending = pending,
            publishPending = {
                if (failPublication) {
                    failPublication = false
                    error("Pending publication failed.")
                }
                publications += pending.snapshot()
            },
        )

        assertFalse(harness.connection.connect(ConnectedB.baseUrl, ConnectedB.credential))
        assertTrue(pending.snapshot().isEmpty())
        assertEquals(listOf("Pending publication failed."), harness.errors.map { it.message })

        assertTrue(harness.connection.connect(ConnectedB.baseUrl, ConnectedB.credential))
        assertEquals(listOf(setOf("connection:1")), publications)
        runCurrent()

        assertEquals(1, client.snapshotCalls)
        assertEquals(ConnectedB, harness.visible)
        assertTrue(pending.snapshot().isEmpty())
    }

    @Test
    fun disconnectDuringPendingPublicationRollsBackBeforeLaunchingCoroutine() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pending = Pending()
        val client = StaticClient()
        val publications = mutableListOf<Set<String>>()
        val disconnectedPending = mutableListOf<Set<String>>()
        var disconnectOnPublish = true
        lateinit var harness: Harness
        harness = Harness(
            backgroundScope,
            dispatcher,
            MemoryStore(null),
            client,
            pending = pending,
            publishPending = {
                publications += pending.snapshot()
                if (disconnectOnPublish) {
                    disconnectOnPublish = false
                    assertTrue(harness.connection.disconnect())
                }
            },
            disconnected = { disconnectedPending += pending.snapshot() },
        )

        val started = harness.connection.connect(ConnectedB.baseUrl, ConnectedB.credential)
        runCurrent()

        assertFalse(started)
        assertEquals(0, client.snapshotCalls)
        assertEquals(1, publications.size)
        assertEquals(listOf(emptySet<String>()), disconnectedPending)
        assertTrue(pending.snapshot().isEmpty())
        assertEquals(null, harness.visible)
        assertTrue(harness.errors.isEmpty())
    }

    @Test
    fun dismissDuringPendingPublicationPreventsPairCoroutineAndLatePublication() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pending = Pending()
        val client = BlockingRevocationClient()
        val invitation = Invitation(
            ConnectedB.normalizedUrl,
            "c".repeat(43),
            Long.MAX_VALUE,
        )
        var publications = 0
        var dismissOnPublish = true
        lateinit var harness: Harness
        harness = Harness(
            backgroundScope,
            dispatcher,
            MemoryStore(null),
            client,
            pending = pending,
            publishPending = {
                publications += 1
                if (dismissOnPublish) {
                    dismissOnPublish = false
                    assertTrue(harness.connection.dismiss(invitation))
                }
            },
        )

        val started = harness.connection.pair(invitation)
        runCurrent()

        assertFalse(started)
        assertTrue(client.claimedInvitations.isEmpty())
        assertEquals(2, publications)
        assertTrue(pending.snapshot().isEmpty())
        assertEquals(null, harness.connection.pendingInvitation)
        assertTrue(harness.errors.isEmpty())
    }

    @Test
    fun dismissalBeforeAttemptRegistrationPreventsPairCoroutine() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = MemoryStore(null)
        val client = BlockingRevocationClient()
        val invitation = Invitation(
            ConnectedB.normalizedUrl,
            "d".repeat(43),
            Long.MAX_VALUE,
        )
        var publications = 0
        lateinit var harness: Harness
        harness = Harness(
            backgroundScope,
            dispatcher,
            store,
            client,
            publishPending = { publications += 1 },
        )
        store.onLoadRevocations = {
            assertTrue(harness.connection.dismiss(invitation))
        }

        val started = harness.connection.pair(invitation)
        runCurrent()

        assertFalse(started)
        assertTrue(client.claimedInvitations.isEmpty())
        assertEquals(0, publications)
        assertEquals(null, store.loadClaim())
        assertEquals(null, harness.connection.pendingInvitation)
        assertTrue(harness.errors.isEmpty())
    }

    @Test
    fun disconnectedAttemptReturningLateDoesNotPublish() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pending = Pending()
        val client = DelayedSnapshotClient()
        var publications = 0
        val harness = Harness(
            backgroundScope,
            dispatcher,
            MemoryStore(null),
            client,
            pending = pending,
            publishPending = { publications += 1 },
        )

        assertTrue(harness.connection.connect(ConnectedB.baseUrl, ConnectedB.credential))
        runCurrent()
        assertTrue(client.entered.isCompleted)

        assertTrue(harness.connection.disconnect())
        assertTrue(pending.snapshot().isEmpty())
        client.release.complete(Unit)
        runCurrent()

        assertEquals(1, publications)
        assertEquals(null, harness.visible)
        assertTrue(harness.errors.isEmpty())
    }

    private class StaticClient : Client {
        var snapshotCalls = 0

        override suspend fun snapshot(config: Config): Snapshot {
            snapshotCalls += 1
            return Snapshot(
                revision = "r_connection",
                status = Status("ready", null),
                capabilities = Capabilities(),
            )
        }

        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class BlockingRevocationClient : Client {
        val revocationAttempts = mutableListOf<Config>()
        val claimedInvitations = mutableListOf<Invitation>()
        var snapshotCalls = 0

        override suspend fun claimPending(invitation: Invitation, nonce: String): IssuedCredential {
            claimedInvitations += invitation
            return IssuedCredential(ConnectedB, Long.MAX_VALUE)
        }

        override suspend fun activate(config: Config) = Unit

        override suspend fun snapshot(config: Config): Snapshot {
            snapshotCalls += 1
            return Snapshot(
                revision = "r_connection",
                status = Status("ready", null),
                capabilities = Capabilities(),
            )
        }

        override suspend fun command(config: Config, command: Command) = Unit

        override suspend fun revoke(config: Config) {
            revocationAttempts += config
            awaitCancellation()
        }
    }

    private class DelayedSnapshotClient : Client {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun snapshot(config: Config): Snapshot {
            entered.complete(Unit)
            release.await()
            return Snapshot(
                revision = "r_connection",
                status = Status("ready", null),
                capabilities = Capabilities(),
            )
        }

        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class Harness(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        store: Store,
        client: Client,
        val pending: Pending = Pending(),
        publishPending: () -> Unit = {},
        disconnected: () -> Unit = {},
    ) {
        var visible: Config? = null
        val errors = mutableListOf<Throwable>()
        val connection = Connection(
            scope = scope,
            dispatcher = dispatcher,
            client = client,
            store = store,
            pending = pending,
            current = { visible },
            publishPending = publishPending,
            connected = { config, _ -> visible = config },
            disconnected = {
                visible = null
                disconnected()
            },
            rejected = errors::add,
            recover = {},
        )
    }

    private class MemoryStore(
        initial: Config?,
        initialRevocations: List<Config> = emptyList(),
    ) : Store {
        @Volatile private var config = initial
        @Volatile private var claim: Claim? = null
        private val revocations = initialRevocations.toMutableList()
        @Volatile private var voiceStop: VoiceStop? = null
        @Volatile private var pendingCommand: PendingCommand? = null
        var onLoadRevocations: (() -> Unit)? = null

        val queuedRevocations: List<Config>
            @Synchronized get() = revocations.toList()

        override fun save(config: Config) {
            this.config = config
        }

        override fun load(): Config? = config
        override fun clear() {
            config = null
        }

        override fun saveClaim(claim: Claim) {
            this.claim = claim
        }

        override fun loadClaim(): Claim? = claim
        override fun clearClaim() {
            claim = null
        }

        @Synchronized
        override fun saveRevocation(config: Config) {
            revocations.clear()
            revocations += config
        }

        @Synchronized
        override fun loadRevocation(): Config? = revocations.firstOrNull()

        @Synchronized
        override fun clearRevocation() {
            revocations.clear()
        }

        @Synchronized
        override fun enqueueRevocation(config: Config) {
            if (revocations.none { it.sameCredential(config) }) revocations += config
        }

        @Synchronized
        override fun loadRevocations(): List<Config> {
            val callback = onLoadRevocations
            onLoadRevocations = null
            callback?.invoke()
            return revocations.toList()
        }

        @Synchronized
        override fun removeRevocation(config: Config): Boolean {
            val index = revocations.indexOfFirst { it.sameCredential(config) }
            if (index < 0) return false
            revocations.removeAt(index)
            return true
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
            pendingCommand = command
        }

        override fun loadPendingCommand(): PendingCommand? = pendingCommand

        override fun markPendingCommandDispatched(operationId: String): PendingCommand {
            val current = pendingCommand
                ?.takeIf { it.operationId == operationId }
                ?: error("Different pending command")
            return current.copy(phase = PendingCommand.Phase.DISPATCH_ATTEMPTED).also {
                pendingCommand = it
            }
        }

        override fun clearPendingCommand(operationId: String): Boolean {
            val current = pendingCommand ?: return true
            if (current.operationId != operationId) return false
            pendingCommand = null
            return true
        }
    }

    private companion object {
        val Original = Config(
            "https://original.example.test",
            "vp1.original.abcdefghijklmnopqrstuvwxyzABCDEFG",
        )
        val Replacement = Config(
            "https://replacement.example.test",
            "vp1.replacement.abcdefghijklmnopqrstuvwxyzABCD",
        )
        val RevokedA = Config(
            "https://bridge-a.example.test",
            "vp1.revoked01.abcdefghijklmnopqrstuvwxyzABCDEFG",
        )
        val ConnectedB = Config(
            "https://bridge-b.example.test",
            "vp1.connected.abcdefghijklmnopqrstuvwxyzABCDEFG",
        )
    }
}

private fun Config.sameCredential(other: Config): Boolean =
    normalizedUrl == other.normalizedUrl && credential == other.credential
