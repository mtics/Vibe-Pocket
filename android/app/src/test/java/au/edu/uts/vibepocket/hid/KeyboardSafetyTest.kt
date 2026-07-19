package au.edu.uts.vibepocket.hid

import au.edu.uts.vibepocket.input.HidResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException

class KeyboardSafetyTest {
    @Test
    fun quarantineHidesConnectionBeforeSchedulingRecovery() {
        val executor = ManualExecutor()
        val quarantine = RecoveryQuarantine<String>(executor)
        val events = mutableListOf<String>()

        assertTrue(
            quarantine.enter(
                candidate = "host-a",
                onEntered = { events += "quarantined" },
                recover = {
                    events += "zero-report"
                    events += "disconnect"
                },
            ),
        )

        assertEquals(listOf("quarantined"), events)
        assertTrue(quarantine.isActive())
        assertEquals(1, executor.pendingCount)

        executor.runNext()

        assertEquals(listOf("quarantined", "zero-report", "disconnect"), events)
        assertTrue(quarantine.isActive())
    }

    @Test
    fun quarantineQueuesOneRecoveryAndRetainsOwnershipUntilMatchingDisconnect() {
        val executor = ManualExecutor()
        val quarantine = RecoveryQuarantine<String>(executor)

        assertTrue(quarantine.enter("host-a", {}, {}))
        assertFalse(quarantine.enter("host-b", {}, {}))
        assertEquals(1, executor.pendingCount)
        assertFalse(quarantine.leave { it == "host-b" })
        assertTrue(quarantine.isActive())

        assertTrue(quarantine.leave { it == "host-a" })
        assertFalse(quarantine.isActive())
    }

    @Test
    fun rejectedRecoveryExecutorLeavesTransportQuarantined() {
        val quarantine = RecoveryQuarantine<String>(Executor { throw RejectedExecutionException() })

        assertTrue(quarantine.enter("host-a", {}, {}))

        assertTrue(quarantine.isActive())
        assertTrue(quarantine.owns { it == "host-a" })
    }

    @Test
    fun zeroReportTimeoutIsCancelledBeforeDisconnect() {
        val release = FutureTask<Unit> {}
        val events = mutableListOf<String>()

        awaitReleaseThenDisconnect(
            release = release,
            awaitRelease = {
                events += "zero-report-timeout"
                throw TimeoutException()
            },
            disconnect = { events += "disconnect" },
        )

        assertTrue(release.isCancelled)
        assertEquals(listOf("zero-report-timeout", "disconnect"), events)
    }

    @Test
    fun uncertainReportIsNotExposedAsConnectedUiState() {
        val failed = afterReportFailure(
            Status(
                registered = true,
                connectedHostAddress = "host-a",
                connectingHostAddress = "host-b",
            ),
        )

        assertFalse(failed.connected)
        assertEquals(null, failed.connectedHostAddress)
        assertEquals(null, failed.connectingHostAddress)
        assertTrue(failed.message.contains("uncertain"))
    }

    @Test
    fun timeoutAndWorkerErrorAreNeverReportedAsDelivered() {
        assertEquals(HidResult.TIMED_OUT, QueueResult.TimedOut.toPressResult())
        assertEquals(HidResult.INDETERMINATE, QueueResult.Failed.toPressResult())
        assertEquals(
            TransactionResult.INDETERMINATE,
            TransactionResult.NOT_DISPATCHED.afterReportException(reportException = true),
        )
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        val pendingCount: Int get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }
}
