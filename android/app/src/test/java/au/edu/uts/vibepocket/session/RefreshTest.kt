package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.bridge.Failure
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Status
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun stormConflatesWithoutCancellingAndAppliesLatest() = runTest(dispatcher) {
        val client = ControlledClient()
        val harness = harness(client, snapshot("base"))
        harness.refresh.activate(ConfigA, 1)

        harness.refresh.request(stale = true)
        assertFalse(requireNotNull(harness.state.value.snapshot).transportFresh)
        assertFalse(harness.state.value.isRefreshing)
        runCurrent()

        repeat(20) { harness.refresh.request(stale = true) }
        assertEquals(1, client.calls.size)
        assertEquals(0, client.cancellations)

        client.succeed(0, snapshot("intermediate"))
        runCurrent()

        assertEquals(2, client.calls.size)
        assertEquals(1, client.maxActive)
        assertEquals(0, client.cancellations)

        client.succeed(1, snapshot("latest"))
        runCurrent()

        assertEquals("latest", harness.state.value.snapshot?.revision)
        assertTrue(harness.state.value.snapshot?.transportFresh == true)
        assertFalse(harness.state.value.isRefreshing)
        assertEquals(0, client.active)
    }

    @Test
    fun leaseChangesAndDeactivateCancelAndRejectOldWork() = runTest(dispatcher) {
        val client = ControlledClient()
        val harness = harness(client, snapshot("base"))
        harness.refresh.activate(ConfigA, 1)
        harness.refresh.request(generation = 1)
        runCurrent()

        harness.refresh.activate(ConfigA, 2)
        runCurrent()
        assertEquals(1, client.cancellations)
        assertNull(harness.refresh.request(generation = 1))
        harness.refresh.request(generation = 2)
        runCurrent()

        harness.state.value = harness.state.value.copy(config = ConfigB)
        harness.refresh.activate(ConfigB, 3)
        runCurrent()
        assertEquals(2, client.cancellations)
        assertNull(harness.refresh.request(generation = 2))
        val current = requireNotNull(harness.refresh.request(generation = 3))
        runCurrent()

        client.succeed(0, snapshot("old-generation"))
        client.succeed(1, snapshot("old-config"))
        client.succeed(2, snapshot("current"))
        runCurrent()

        assertEquals("current", harness.state.value.snapshot?.revision)
        assertEquals(listOf("current" to current), harness.reconciled)

        harness.refresh.request(generation = 3)
        runCurrent()
        harness.refresh.deactivate()
        runCurrent()
        assertEquals(3, client.cancellations)
        assertNull(harness.refresh.request())

        client.succeed(3, snapshot("after-deactivate"))
        runCurrent()
        assertEquals("current", harness.state.value.snapshot?.revision)
        assertEquals(listOf("current" to current), harness.reconciled)
    }

    @Test
    fun errorStillRunsQueuedSuccess() = runTest(dispatcher) {
        val client = ControlledClient()
        val harness = harness(client, null)
        harness.refresh.activate(ConfigA, 1)
        harness.refresh.request()
        runCurrent()
        val queued = requireNotNull(harness.refresh.request())

        client.fail(0, IOException("first failed"))
        runCurrent()

        assertEquals(2, client.calls.size)
        assertEquals("first failed", harness.state.value.error)
        assertTrue(harness.state.value.isRefreshing)
        assertEquals(0, client.cancellations)

        client.succeed(1, snapshot("recovered"))
        runCurrent()

        assertEquals("recovered", harness.state.value.snapshot?.revision)
        assertNull(harness.state.value.error)
        assertFalse(harness.state.value.isRefreshing)
        assertEquals(listOf("recovered" to queued), harness.reconciled)
    }

    @Test
    fun incompatibleRefreshKeepsTheVisibleSnapshotStaleAndUnreconciled() = runTest(dispatcher) {
        val client = ControlledClient()
        val harness = harness(client, snapshot("compatible"))
        harness.refresh.activate(ConfigA, 1)
        harness.refresh.request(stale = true)
        runCurrent()

        client.fail(
            0,
            Failure("The Vibe Pocket bridge returned an incompatible snapshot protocol version."),
        )
        runCurrent()

        assertEquals("compatible", harness.state.value.snapshot?.revision)
        assertFalse(harness.state.value.snapshot?.transportFresh == true)
        assertEquals(
            "The Vibe Pocket bridge returned an incompatible snapshot protocol version.",
            harness.state.value.error,
        )
        assertTrue(harness.reconciled.isEmpty())
    }

    @Test
    fun callbacksUseAppliedRequestVersions() = runTest(dispatcher) {
        val client = ControlledClient()
        val harness = harness(client, snapshot("base"))
        val prepared = mutableListOf<Long>()
        harness.refresh.activate(ConfigA, 1)

        val first = requireNotNull(harness.refresh.request(onPrepared = prepared::add))
        runCurrent()
        val replaced = requireNotNull(harness.refresh.request(onPrepared = prepared::add))
        val latest = requireNotNull(harness.refresh.request(onPrepared = prepared::add))

        client.succeed(0, snapshot("first"))
        runCurrent()

        assertEquals("first", harness.state.value.snapshot?.revision)
        assertEquals(listOf("first" to first), harness.reconciled)
        assertEquals(2, client.calls.size)

        client.succeed(1, snapshot("latest"))
        runCurrent()

        assertEquals(listOf(first, replaced, latest), prepared)
        assertTrue(first < replaced && replaced < latest)
        assertEquals(listOf("first" to first, "latest" to latest), harness.reconciled)
        assertFalse(harness.reconciled.any { it.second == replaced })
    }

    @Test
    fun storesRevisionAndMessageOnlyAuthoritativeChanges() = runTest(dispatcher) {
        val client = ControlledClient()
        val initial = snapshot("r_1", message = "Waiting for approval")
        val harness = harness(client, initial)
        harness.refresh.activate(ConfigA, 1)
        val request = requireNotNull(harness.refresh.request())
        runCurrent()

        client.succeed(0, snapshot("r_2", message = "Tests are running"))
        runCurrent()

        assertEquals("r_2", harness.state.value.snapshot?.revision)
        assertEquals("Tests are running", harness.state.value.snapshot?.status?.message)
        assertEquals(listOf("r_2" to request), harness.reconciled)
    }

    private fun TestScope.harness(client: Client, initial: Snapshot?): Harness {
        val state = MutableStateFlow(State(config = ConfigA, snapshot = initial))
        val reconciled = mutableListOf<Pair<String, Long>>()
        val refresh = Refresh(
            scope = this,
            dispatcher = dispatcher,
            client = client,
            state = state,
            reconcile = { remote, _ -> remote },
            reconciled = { value, version -> reconciled += value.revision to version },
        )
        return Harness(refresh, state, reconciled)
    }

    private data class Harness(
        val refresh: Refresh,
        val state: MutableStateFlow<State>,
        val reconciled: MutableList<Pair<String, Long>>,
    )

    private class ControlledClient : Client {
        data class Call(
            val config: Config,
            val result: CompletableDeferred<Snapshot> = CompletableDeferred(),
        )

        val calls = mutableListOf<Call>()
        var active = 0
            private set
        var maxActive = 0
            private set
        var cancellations = 0
            private set

        override suspend fun snapshot(config: Config): Snapshot {
            val call = Call(config)
            calls += call
            active += 1
            maxActive = maxOf(maxActive, active)
            return try {
                call.result.await()
            } catch (error: CancellationException) {
                cancellations += 1
                throw error
            } finally {
                active -= 1
            }
        }

        override suspend fun command(config: Config, command: Command) = Unit

        fun succeed(index: Int, value: Snapshot) {
            calls[index].result.complete(value)
        }

        fun fail(index: Int, error: Throwable) {
            calls[index].result.completeExceptionally(error)
        }
    }

    private companion object {
        val ConfigA = Config("https://a.example.test", "a".repeat(24))
        val ConfigB = Config("https://b.example.test", "b".repeat(24))

        fun snapshot(revision: String, message: String? = null) = Snapshot(
            revision = revision,
            status = Status("ready", message),
            capabilities = Capabilities(),
        )
    }
}
