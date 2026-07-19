package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.connection.Claim
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.PendingCommand
import au.edu.uts.vibepocket.connection.Store
import au.edu.uts.vibepocket.connection.VoiceStop
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Status
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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

    private class StaticClient : Client {
        override suspend fun snapshot(config: Config): Snapshot = Snapshot(
            revision = "r_connection",
            status = Status("ready", null),
            capabilities = Capabilities(),
        )

        override suspend fun command(config: Config, command: Command) = Unit
    }

    private class MemoryStore(initial: Config?) : Store {
        @Volatile private var config = initial
        @Volatile private var claim: Claim? = null
        @Volatile private var revocation: Config? = null
        @Volatile private var voiceStop: VoiceStop? = null
        @Volatile private var pendingCommand: PendingCommand? = null

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

        override fun saveRevocation(config: Config) {
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
        val Original = Config(
            "https://original.example.test",
            "vp1.original.abcdefghijklmnopqrstuvwxyzABCDEFG",
        )
        val Replacement = Config(
            "https://replacement.example.test",
            "vp1.replacement.abcdefghijklmnopqrstuvwxyzABCD",
        )
    }
}
