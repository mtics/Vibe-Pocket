package au.edu.uts.vibepocket.hid

import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal enum class TransactionResult {
    COMPLETE,
    NOT_DISPATCHED,
    INDETERMINATE,
}

internal sealed interface QueueResult<out T> {
    data class Completed<T>(val value: T) : QueueResult<T>
    data object Rejected : QueueResult<Nothing>
    data object Cancelled : QueueResult<Nothing>
    data object TimedOut : QueueResult<Nothing>
    data object Failed : QueueResult<Nothing>
}

internal class ProducerGate {
    private val lock = Any()
    private var closed = false
    private var paused = false
    private var generation = 0L

    fun submit(block: () -> Boolean): Boolean = synchronized(lock) {
        if (closed || paused) false else block()
    }

    fun submitVersioned(block: (Long) -> Boolean): Boolean = synchronized(lock) {
        if (closed || paused) false else block(generation)
    }

    fun isCurrent(ticket: Long): Boolean = synchronized(lock) {
        !closed && !paused && ticket == generation
    }

    fun cancelPending(): Boolean = synchronized(lock) {
        if (closed || paused) return@synchronized false
        generation += 1
        true
    }

    fun pause(schedule: () -> Boolean): Boolean = synchronized(lock) {
        if (closed || paused) return@synchronized false
        paused = true
        generation += 1
        if (schedule()) true else {
            paused = false
            false
        }
    }

    fun resume(): Boolean = synchronized(lock) {
        if (closed || !paused) return@synchronized false
        paused = false
        generation += 1
        true
    }

    fun close(finalRelease: () -> Unit): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        closed = true
        generation += 1
        finalRelease()
        true
    }
}

internal class ReportQueue(
    private val executor: ExecutorService,
    private val waitTimeoutMillis: Long = DEFAULT_WAIT_TIMEOUT_MILLIS,
    private val maxPendingReports: Int = DEFAULT_MAX_PENDING_REPORTS,
) {
    private val gate = ProducerGate()
    private val queueLock = Any()
    private val pending = ArrayDeque<QueuedReport>()
    private var workerScheduled = false

    init {
        require(waitTimeoutMillis > 0)
        require(maxPendingReports > 0)
    }

    fun submit(
        block: () -> Unit,
        cancelled: () -> Unit = {},
        failed: () -> Unit = {},
    ): Boolean = gate.submitVersioned { ticket ->
        enqueue(
            QueuedReport(
                ticket = ticket,
                run = {
                    runCatching(block).onFailure { runCatching(failed) }
                },
                cancel = { runCatching(cancelled) },
            ),
        )
    }

    fun <T> submitAndWait(block: () -> T): QueueResult<T> {
        val completion = CompletableFuture<QueueResult<T>>()
        val accepted = gate.submitVersioned { ticket ->
            enqueue(
                QueuedReport(
                    ticket = ticket,
                    run = {
                        completion.complete(
                            runCatching(block).fold(
                                onSuccess = { QueueResult.Completed(it) },
                                onFailure = { QueueResult.Failed },
                            ),
                        )
                    },
                    cancel = { completion.complete(QueueResult.Cancelled) },
                ),
            )
        }
        if (!accepted) return QueueResult.Rejected
        return await(completion)
    }

    fun cancelPending(): Boolean {
        if (!gate.cancelPending()) return false
        cancel(discardPending())
        return true
    }

    internal fun pendingCount(): Int = synchronized(queueLock) { pending.size }

    fun pause(finalRelease: () -> Unit, completed: (QueueResult<Unit>) -> Unit): Boolean {
        var stale = emptyList<QueuedReport>()
        val scheduled = gate.pause {
            stale = discardPending()
            enqueue(
                QueuedReport(
                    ticket = null,
                    run = {
                        val result = runCatching(finalRelease).fold(
                            onSuccess = { QueueResult.Completed(Unit) },
                            onFailure = { QueueResult.Failed },
                        )
                        runCatching { completed(result) }
                    },
                ),
                priority = true,
                bypassCapacity = true,
            )
        }
        cancel(stale)
        return scheduled
    }

    fun resume(): Boolean = gate.resume()

    fun close(finalRelease: () -> Unit): QueueResult<Unit> {
        val completion = CompletableFuture<QueueResult<Unit>>()
        var stale = emptyList<QueuedReport>()
        val closing = gate.close {
            stale = discardPending()
            val scheduled = enqueue(
                QueuedReport(
                    ticket = null,
                    run = {
                        completion.complete(
                            runCatching(finalRelease).fold(
                                onSuccess = { QueueResult.Completed(Unit) },
                                onFailure = { QueueResult.Failed },
                            ),
                        )
                    },
                ),
                priority = true,
                bypassCapacity = true,
            )
            if (!scheduled) completion.complete(QueueResult.Failed)
        }
        cancel(stale)
        if (!closing) return QueueResult.Rejected
        executor.shutdown()
        val primary = await(completion)
        if (primary is QueueResult.Completed) {
            executor.shutdownNow()
            return primary
        }
        cancel(discardPending())
        executor.shutdownNow()
        return attemptFinalRelease(finalRelease)
    }

    private fun enqueue(
        report: QueuedReport,
        priority: Boolean = false,
        bypassCapacity: Boolean = false,
    ): Boolean {
        val scheduleWorker = synchronized(queueLock) {
            if (!bypassCapacity && pending.size >= maxPendingReports) return false
            if (priority) pending.addFirst(report) else pending.addLast(report)
            (!workerScheduled).also { if (it) workerScheduled = true }
        }
        if (!scheduleWorker) return true
        val scheduled = runCatching { executor.execute(::drain) }.isSuccess
        if (!scheduled) {
            val abandoned = synchronized(queueLock) {
                workerScheduled = false
                pending.toList().also { pending.clear() }
            }
            cancel(abandoned)
        }
        return scheduled
    }

    private fun drain() {
        while (true) {
            val report = synchronized(queueLock) {
                if (pending.isEmpty()) {
                    workerScheduled = false
                    null
                } else {
                    pending.removeFirst()
                }
            } ?: return
            if (report.ticket == null || gate.isCurrent(report.ticket)) {
                report.run()
            } else {
                report.cancel()
            }
        }
    }

    private fun discardPending(): List<QueuedReport> = synchronized(queueLock) {
        pending.toList().also { pending.clear() }
    }

    private fun cancel(reports: List<QueuedReport>) {
        reports.forEach { report -> report.cancel() }
    }

    private fun attemptFinalRelease(finalRelease: () -> Unit): QueueResult<Unit> {
        val release = FutureTask<QueueResult<Unit>> {
            runCatching(finalRelease).fold(
                onSuccess = { QueueResult.Completed(Unit) },
                onFailure = { QueueResult.Failed },
            )
        }
        Thread(release, "vibe-hid-final-release").apply {
            isDaemon = true
            start()
        }
        return await(release)
    }

    private data class QueuedReport(
        val ticket: Long?,
        val run: () -> Unit,
        val cancel: () -> Unit = {},
    )

    private fun <T> await(future: Future<QueueResult<T>>): QueueResult<T> = try {
        future.get(waitTimeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        future.cancel(true)
        Thread.currentThread().interrupt()
        QueueResult.Cancelled
    } catch (_: TimeoutException) {
        future.cancel(true)
        QueueResult.TimedOut
    } catch (_: CancellationException) {
        QueueResult.Cancelled
    } catch (_: ExecutionException) {
        QueueResult.Failed
    }

    private companion object {
        const val DEFAULT_WAIT_TIMEOUT_MILLIS = 500L
        const val DEFAULT_MAX_PENDING_REPORTS = 8
    }
}

internal fun newReportExecutor(): ExecutorService = ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(1),
    { runnable ->
        Thread(runnable, "vibe-hid-report").apply { isDaemon = true }
    },
    ThreadPoolExecutor.AbortPolicy(),
)

internal fun reconnectAfterAdapterChange(enabled: Boolean, connectPermission: Boolean): Boolean =
    enabled && connectPermission

internal fun sendTransaction(
    chords: List<Chord>,
    send: (ByteArray) -> Boolean,
    pause: (Long) -> Unit,
    holdMillis: Long,
    gapMillis: Long,
): TransactionResult {
    var dispatched = false
    chords.forEach { chord ->
        val keyDown = try {
            send(Report.encode(chord))
        } catch (_: RuntimeException) {
            false
        }
        if (!keyDown) {
            return if (dispatched) TransactionResult.INDETERMINATE else TransactionResult.NOT_DISPATCHED
        }
        dispatched = true
        try {
            pause(holdMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return TransactionResult.INDETERMINATE
        }
        val released = try {
            send(Report.release)
        } catch (_: RuntimeException) {
            false
        }
        if (!released) return TransactionResult.INDETERMINATE
        try {
            pause(gapMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return TransactionResult.INDETERMINATE
        }
    }
    return TransactionResult.COMPLETE
}

internal fun sendSequence(
    transactions: List<List<Chord>>,
    send: (ByteArray) -> Boolean,
    pause: (Long) -> Unit,
    ready: () -> Boolean,
    holdMillis: Long,
    gapMillis: Long,
    intervalMillis: Long,
): TransactionResult {
    var dispatched = false
    transactions.forEachIndexed { index, chords ->
        if (!ready()) {
            return if (dispatched) TransactionResult.INDETERMINATE else TransactionResult.NOT_DISPATCHED
        }
        val result = sendTransaction(chords, send, pause, holdMillis, gapMillis)
        if (result != TransactionResult.COMPLETE) {
            return if (dispatched) TransactionResult.INDETERMINATE else result
        }
        dispatched = true
        if (index < transactions.lastIndex) {
            try {
                pause(intervalMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return TransactionResult.INDETERMINATE
            }
        }
    }
    return if (dispatched) TransactionResult.COMPLETE else TransactionResult.NOT_DISPATCHED
}

internal data class ConnectionAfterDisconnect(
    val connectedAddress: String?,
    val connectingAddress: String?,
)

internal fun afterDisconnect(
    disconnectedAddress: String,
    trackedConnectedAddress: String?,
    trackedConnectingAddress: String?,
    actualConnectedAddress: String?,
): ConnectionAfterDisconnect = ConnectionAfterDisconnect(
    connectedAddress = actualConnectedAddress?.takeUnless { it == disconnectedAddress }
        ?: trackedConnectedAddress?.takeUnless { it == disconnectedAddress },
    connectingAddress = if (actualConnectedAddress != null && actualConnectedAddress != disconnectedAddress) {
        null
    } else {
        trackedConnectingAddress?.takeUnless { it == disconnectedAddress }
    },
)
