package au.edu.uts.vibepocket.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class TransportTest {
    @Test
    fun finalReleaseClosesTheProducerBarrier() {
        val queued = mutableListOf<String>()
        val gate = ProducerGate()

        assertEquals(true, gate.submit { queued += "action"; true })
        assertEquals(true, gate.close { queued += "release" })
        assertEquals(false, gate.submit { queued += "late"; true })
        assertEquals(false, gate.close { queued += "second-release" })

        assertEquals(listOf("action", "release"), queued)
    }

    @Test
    fun synchronousSubmissionReturnsTheCompletedReportResult() {
        val reports = ReportQueue(Executors.newSingleThreadExecutor())
        val entered = CountDownLatch(1)
        val continueReport = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()

        val result = caller.submit<QueueResult<Boolean>> {
            reports.submitAndWait {
                entered.countDown()
                continueReport.await()
                false
            }
        }

        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertFalse(result.isDone)
        continueReport.countDown()
        assertEquals(QueueResult.Completed(false), result.get(1, TimeUnit.SECONDS))

        assertEquals(QueueResult.Completed(Unit), reports.close {})
        caller.shutdownNow()
    }

    @Test
    fun closeIsBoundedWhenAReportWorkerDoesNotReturn() {
        val reports = ReportQueue(Executors.newSingleThreadExecutor(), waitTimeoutMillis = 75)
        val workStarted = CountDownLatch(1)
        val continueWork = CountDownLatch(1)
        val order = mutableListOf<String>()

        assertTrue(
            reports.submit(block = {
                workStarted.countDown()
                continueWork.await()
                synchronized(order) { order += "work" }
            }),
        )
        assertTrue(workStarted.await(1, TimeUnit.SECONDS))

        val started = System.nanoTime()
        assertEquals(
            QueueResult.TimedOut,
            reports.close { synchronized(order) { order += "release" } },
        )
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue(elapsedMillis < 1_000)
        assertEquals(emptyList<String>(), synchronized(order) { order.toList() })
        continueWork.countDown()
        assertFalse(reports.submit(block = { synchronized(order) { order += "late" } }))
    }

    @Test
    fun cancelledPendingReportNeverExecutes() {
        val reports = ReportQueue(Executors.newSingleThreadExecutor())
        val workStarted = CountDownLatch(1)
        val continueWork = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        var lateReportRan = false

        assertTrue(
            reports.submit(block = {
                workStarted.countDown()
                continueWork.await()
            }),
        )
        assertTrue(workStarted.await(1, TimeUnit.SECONDS))
        assertTrue(
            reports.submit(
                block = { lateReportRan = true },
                cancelled = { cancelled.countDown() },
            ),
        )

        assertTrue(reports.cancelPending())
        continueWork.countDown()

        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        assertFalse(lateReportRan)
        assertEquals(QueueResult.Completed(Unit), reports.close {})
    }

    @Test
    fun synchronousWaitReportsLifecycleCancellation() {
        val executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
        )
        val reports = ReportQueue(executor)
        val workStarted = CountDownLatch(1)
        val continueWork = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()

        assertTrue(
            reports.submit(block = {
                workStarted.countDown()
                continueWork.await()
            }),
        )
        assertTrue(workStarted.await(1, TimeUnit.SECONDS))
        val waiting = caller.submit<QueueResult<Boolean>> { reports.submitAndWait { true } }
        val queuedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (executor.queue.isEmpty() && System.nanoTime() < queuedDeadline) Thread.yield()
        assertFalse(executor.queue.isEmpty())

        assertTrue(reports.cancelPending())
        continueWork.countDown()

        assertEquals(QueueResult.Cancelled, waiting.get(1, TimeUnit.SECONDS))
        assertEquals(QueueResult.Completed(Unit), reports.close {})
        caller.shutdownNow()
    }

    @Test
    fun synchronousWaitReportsTimeoutWithoutBlockingIndefinitely() {
        val reports = ReportQueue(Executors.newSingleThreadExecutor(), waitTimeoutMillis = 75)
        val entered = CountDownLatch(1)
        val never = CountDownLatch(1)

        val started = System.nanoTime()
        val result = reports.submitAndWait {
            entered.countDown()
            never.await()
            true
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals(QueueResult.TimedOut, result)
        assertTrue(elapsedMillis < 1_000)
        reports.close {}
    }

    @Test
    fun adapterReconnectRequiresConnectPermission() {
        assertTrue(reconnectAfterAdapterChange(enabled = true, connectPermission = true))
        assertFalse(reconnectAfterAdapterChange(enabled = true, connectPermission = false))
        assertFalse(reconnectAfterAdapterChange(enabled = false, connectPermission = true))
    }

    @Test
    fun transactionCompletesOnlyAfterEveryKeyDownAndRelease() {
        val reports = mutableListOf<ByteArray>()
        val pauses = mutableListOf<Long>()
        val chords = listOf(Chord(usage = Report.USAGE_A), Chord(usage = Report.USAGE_BACKSPACE))

        val result = sendTransaction(chords, { reports += it; true }, { pauses += it }, 24, 12)

        assertEquals(TransactionResult.COMPLETE, result)
        assertEquals(4, reports.size)
        assertEquals(listOf(24L, 12L, 24L, 12L), pauses)
        assertEquals(Report.USAGE_A.toByte(), reports[0][2])
        assertEquals(ByteArray(8).toList(), reports[1].toList())
    }

    @Test
    fun keyDownFailureDoesNotPauseOrClaimCompletion() {
        val pauses = mutableListOf<Long>()

        val result = sendTransaction(
            listOf(Chord(usage = Report.USAGE_ENTER)),
            send = { false },
            pause = { pauses += it },
            holdMillis = 24,
            gapMillis = 12,
        )

        assertEquals(TransactionResult.NOT_DISPATCHED, result)
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test
    fun releaseFailureRequiresRecovery() {
        var sends = 0

        val result = sendTransaction(
            listOf(Chord(usage = Report.USAGE_ENTER)),
            send = { sends += 1; sends == 1 },
            pause = {},
            holdMillis = 24,
            gapMillis = 12,
        )

        assertEquals(TransactionResult.INDETERMINATE, result)
        assertEquals(2, sends)
    }

    @Test
    fun laterChordFailureIsConsumedAndIndeterminate() {
        var sends = 0

        val result = sendTransaction(
            listOf(Chord(usage = Report.USAGE_A), Chord(usage = Report.USAGE_BACKSPACE)),
            send = {
                sends += 1
                sends <= 2
            },
            pause = {},
            holdMillis = 24,
            gapMillis = 12,
        )

        assertEquals(TransactionResult.INDETERMINATE, result)
        assertEquals(3, sends)
    }

    @Test
    fun permissionLossClearsTrackedAndConnectingHosts() {
        val status = Status(
            enabled = true,
            registered = true,
            pairedHosts = listOf(Host("paired", "Mac")),
            connectedHostAddress = "connected",
            connectingHostAddress = "connecting",
        )

        val cleared = afterPermissionLoss(status)

        assertNull(cleared.connectedHostAddress)
        assertNull(cleared.connectingHostAddress)
        assertTrue(cleared.pairedHosts.isEmpty())
        assertFalse(cleared.connected)
    }

    @Test
    fun staleDisconnectDoesNotEraseANewerConnection() {
        assertEquals(
            ConnectionAfterDisconnect("new", null),
            afterDisconnect("old", "new", null, null),
        )
        assertEquals(
            ConnectionAfterDisconnect("actual", null),
            afterDisconnect("old", "old", null, "actual"),
        )
        assertEquals(
            ConnectionAfterDisconnect("new", null),
            afterDisconnect("old", "new", null, "old"),
        )
        assertEquals(
            ConnectionAfterDisconnect(null, "new"),
            afterDisconnect("old", null, "new", null),
        )
    }
}
