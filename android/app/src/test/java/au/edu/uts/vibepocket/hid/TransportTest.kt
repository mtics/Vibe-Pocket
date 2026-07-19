package au.edu.uts.vibepocket.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

        val result = caller.submit<Boolean> {
            reports.submitAndWait {
                entered.countDown()
                continueReport.await()
                false
            }
        }

        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertFalse(result.isDone)
        continueReport.countDown()
        assertFalse(result.get(1, TimeUnit.SECONDS))

        assertTrue(reports.close {})
        caller.shutdownNow()
    }

    @Test
    fun closeWaitsBeyondFormerTimeoutAndDeliversFinalReleaseLast() {
        val reports = ReportQueue(Executors.newSingleThreadExecutor())
        val workStarted = CountDownLatch(1)
        val continueWork = CountDownLatch(1)
        val order = mutableListOf<String>()
        val caller = Executors.newSingleThreadExecutor()

        assertTrue(
            reports.submit {
                workStarted.countDown()
                continueWork.await()
                synchronized(order) { order += "work" }
            },
        )
        assertTrue(workStarted.await(1, TimeUnit.SECONDS))
        val closing = caller.submit<Boolean> {
            reports.close { synchronized(order) { order += "release" } }
        }

        assertThrows(TimeoutException::class.java) { closing.get(300, TimeUnit.MILLISECONDS) }
        assertEquals(emptyList<String>(), synchronized(order) { order.toList() })
        continueWork.countDown()
        assertTrue(closing.get(1, TimeUnit.SECONDS))
        assertEquals(listOf("work", "release"), synchronized(order) { order.toList() })
        assertFalse(reports.submit { synchronized(order) { order += "late" } })

        caller.shutdownNow()
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

        assertEquals(TransactionResult.KEY_DOWN_FAILED, result)
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

        assertEquals(TransactionResult.RELEASE_FAILED, result)
        assertEquals(2, sends)
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
