package au.edu.uts.vibepocket.hid

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
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
    private var generation = 0L

    fun submit(block: () -> Boolean): Boolean = synchronized(lock) {
        if (closed) false else block()
    }

    fun submitVersioned(block: (Long) -> Boolean): Boolean = synchronized(lock) {
        if (closed) false else block(generation)
    }

    fun isCurrent(ticket: Long): Boolean = synchronized(lock) {
        !closed && ticket == generation
    }

    fun cancelPending(): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
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
) {
    private val gate = ProducerGate()

    init {
        require(waitTimeoutMillis > 0)
    }

    fun submit(
        block: () -> Unit,
        cancelled: () -> Unit = {},
        failed: () -> Unit = {},
    ): Boolean = gate.submitVersioned { ticket ->
        runCatching {
            executor.execute {
                if (!gate.isCurrent(ticket)) {
                    runCatching(cancelled)
                } else {
                    runCatching(block).onFailure { runCatching(failed) }
                }
            }
        }.isSuccess
    }

    fun <T> submitAndWait(block: () -> T): QueueResult<T> {
        var future: Future<QueueResult<T>>? = null
        val accepted = gate.submitVersioned { ticket ->
            future = runCatching {
                executor.submit<QueueResult<T>> {
                    if (!gate.isCurrent(ticket)) {
                        QueueResult.Cancelled
                    } else {
                        runCatching(block).fold(
                            onSuccess = { QueueResult.Completed(it) },
                            onFailure = { QueueResult.Failed },
                        )
                    }
                }
            }.getOrNull()
            future != null
        }
        if (!accepted) return QueueResult.Rejected
        return await(future ?: return QueueResult.Rejected)
    }

    fun cancelPending(): Boolean = gate.cancelPending()

    fun close(finalRelease: () -> Unit): QueueResult<Unit> {
        var release: Future<QueueResult<Unit>>? = null
        val closing = gate.close {
            release = runCatching {
                executor.submit<QueueResult<Unit>> {
                    runCatching(finalRelease).fold(
                        onSuccess = { QueueResult.Completed(Unit) },
                        onFailure = { QueueResult.Failed },
                    )
                }
            }.getOrNull()
        }
        if (!closing) return QueueResult.Rejected
        executor.shutdown()
        val result = release?.let(::await) ?: QueueResult.Failed
        executor.shutdownNow()
        return result
    }

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
    }
}

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
