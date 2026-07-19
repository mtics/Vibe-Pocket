package au.edu.uts.vibepocket.hid

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

internal enum class TransactionResult {
    COMPLETE,
    KEY_DOWN_FAILED,
    RELEASE_FAILED,
}

internal class ProducerGate {
    private val lock = Any()
    private var closed = false

    fun submit(block: () -> Boolean): Boolean = synchronized(lock) {
        if (closed) false else block()
    }

    fun close(finalRelease: () -> Unit): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        closed = true
        finalRelease()
        true
    }
}

internal class ReportQueue(
    private val executor: ExecutorService,
) {
    private val gate = ProducerGate()

    fun submit(block: () -> Unit): Boolean = gate.submit {
        runCatching { executor.execute(block) }.isSuccess
    }

    fun submitAndWait(block: () -> Boolean): Boolean {
        var future: Future<Boolean>? = null
        val accepted = gate.submit {
            future = runCatching { executor.submit<Boolean>(block) }.getOrNull()
            future != null
        }
        if (!accepted) return false
        return await(future ?: return false, failure = false)
    }

    fun close(finalRelease: () -> Unit): Boolean {
        var release: Future<Unit>? = null
        val closing = gate.close {
            release = runCatching {
                executor.submit<Unit> {
                    finalRelease()
                    Unit
                }
            }.getOrNull()
        }
        if (!closing) return false
        executor.shutdown()
        release?.let { await(it, Unit) }
        return true
    }

    private fun <T> await(future: Future<T>, failure: T): T {
        var interrupted = false
        return try {
            while (true) {
                try {
                    return future.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (_: ExecutionException) {
                    return failure
                }
            }
            @Suppress("UNREACHABLE_CODE")
            failure
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
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
    chords.forEach { chord ->
        if (!send(Report.encode(chord))) return TransactionResult.KEY_DOWN_FAILED
        pause(holdMillis)
        if (!send(Report.release)) return TransactionResult.RELEASE_FAILED
        pause(gapMillis)
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
