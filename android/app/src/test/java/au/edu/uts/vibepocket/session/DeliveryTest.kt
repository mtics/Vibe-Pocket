package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryTest {
    @Test
    fun distinctCommandsCannotArriveInReverseHttpOrder() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = GateClient()
        val delivery = delivery(dispatcher, client)

        assertTrue(delivery.send(Command.Approve, "approve"))
        assertTrue(delivery.send(Command.Reject, "reject"))
        runCurrent()
        assertEquals(listOf(Command.Approve), client.commands)

        client.first.release.complete(Unit)
        runCurrent()
        assertEquals(listOf(Command.Approve, Command.Reject), client.commands)
    }

    @Test
    fun lastSelectionWinsWithinAReversibleQueueSegment() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = GateClient()
        val delivery = delivery(dispatcher, client)

        delivery.send(Command.Approve, "approve")
        runCurrent()
        delivery.send(Command.SelectLayer("layer-1"), "layer:1", "layer")
        delivery.send(Command.SelectModel("model-1"), "model:1", "model")
        delivery.send(Command.SelectLayer("layer-2"), "layer:2", "layer")

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
    fun irreversibleCommandIsACoalescingBarrier() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = GateClient()
        val delivery = delivery(dispatcher, client)

        delivery.send(Command.Approve, "approve")
        runCurrent()
        delivery.send(Command.SelectLayer("layer-1"), "layer:1", "layer")
        delivery.send(Command.Reject, "reject")
        delivery.send(Command.SelectLayer("layer-2"), "layer:2", "layer")

        client.first.release.complete(Unit)
        runCurrent()

        assertEquals(
            listOf(
                Command.Approve,
                Command.SelectLayer("layer-1"),
                Command.Reject,
                Command.SelectLayer("layer-2"),
            ),
            client.commands,
        )
    }

    @Test
    fun cancellingAnEpochDropsQueuedCommandsAndLateCallbacks() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = GateClient()
        val pending = Pending()
        var accepted = 0
        val delivery = delivery(dispatcher, client, pending) { accepted += 1 }

        assertTrue(delivery.send(Command.Approve, "approve"))
        runCurrent()
        assertTrue(delivery.send(Command.Reject, "reject"))

        delivery.cancel()
        assertTrue(delivery.send(Command.SelectLayer("layer-new"), "layer:new"))
        runCurrent()

        assertEquals(listOf(Command.Approve, Command.SelectLayer("layer-new")), client.commands)
        assertEquals(1, accepted)
        assertFalse(pending.any { it == "approve" || it == "reject" })
    }

    private fun kotlinx.coroutines.test.TestScope.delivery(
        dispatcher: CoroutineDispatcher,
        client: Client,
        pending: Pending = Pending(),
        accepted: () -> Unit = {},
    ): Delivery {
        val config = Config("https://m5.example.test", "0123456789abcdefghijklmn")
        return Delivery(
            scope = this,
            dispatcher = dispatcher,
            client = client,
            pending = pending,
            publishPending = {},
            accepted = { accepted() },
            rejected = { _, error -> throw error },
        ).also { it.bind(config) }
    }

    private class GateClient : Client {
        data class First(val release: CompletableDeferred<Unit> = CompletableDeferred())

        val first = First()
        val commands = mutableListOf<Command>()

        override suspend fun snapshot(config: Config): Snapshot = error("Not used")

        override suspend fun command(config: Config, command: Command) {
            commands += command
            if (commands.size == 1) first.release.await()
        }
    }
}
