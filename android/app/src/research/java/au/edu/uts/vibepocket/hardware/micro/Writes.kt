package au.edu.uts.vibepocket.hardware.micro

import au.edu.uts.vibepocket.hardware.micro.protocol.Frame

internal data class Connection<T>(
    val generation: Long,
    val host: T,
)

internal enum class WriteFault {
    TARGET,
    BOUNDARY,
    OVERLAP,
    LIMIT,
    INCOMPLETE,
    POISONED,
}

internal sealed interface Stage {
    data class Echo(val offset: Int, val value: ByteArray) : Stage
    data class Rejected(val fault: WriteFault) : Stage
}

internal sealed interface Execution {
    data object Empty : Execution
    data object Cancelled : Execution
    data class Ready(val body: ByteArray) : Execution
    data class Rejected(val fault: WriteFault) : Execution
}

internal class Writes<T, U>(
    private val maxFragments: Int = 8,
    private val maxBytes: Int = Frame.bodySize + 1,
    private val timeoutMs: Long = 750,
) {
    private data class Transaction<T, U>(
        val connection: Connection<T>,
        val target: U,
        val bytes: ByteArray,
        val occupied: BooleanArray,
        var fragments: Int,
        var updatedAt: Long,
        var fault: WriteFault? = null,
    )

    private var transaction: Transaction<T, U>? = null

    init {
        require(maxFragments > 0)
        require(maxBytes == Frame.bodySize + 1)
        require(timeoutMs > 0)
    }

    fun stage(
        connection: Connection<T>,
        target: U,
        output: U,
        offset: Int,
        value: ByteArray,
        now: Long,
    ): Stage {
        val current = transaction ?: Transaction(
            connection = connection,
            target = output,
            bytes = ByteArray(maxBytes),
            occupied = BooleanArray(maxBytes),
            fragments = 0,
            updatedAt = now,
        ).also { transaction = it }

        current.updatedAt = now
        val fault = when {
            current.fault != null -> current.fault
            current.connection != connection || current.target != output || target != output -> WriteFault.TARGET
            current.fragments >= maxFragments -> WriteFault.LIMIT
            offset < 0 || offset > maxBytes || value.size > maxBytes - offset -> WriteFault.BOUNDARY
            value.indices.any { current.occupied[offset + it] } -> WriteFault.OVERLAP
            else -> null
        }
        if (fault != null) {
            current.fault = fault
            return Stage.Rejected(fault)
        }

        value.copyInto(current.bytes, offset)
        value.indices.forEach { current.occupied[offset + it] = true }
        current.fragments += 1
        return Stage.Echo(offset, value.copyOf())
    }

    fun execute(connection: Connection<T>, execute: Boolean): Execution {
        val current = transaction ?: return Execution.Empty
        transaction = null
        if (!execute) return Execution.Cancelled
        if (current.connection != connection) return Execution.Rejected(WriteFault.TARGET)
        current.fault?.let { return Execution.Rejected(WriteFault.POISONED) }

        val end = current.occupied.indexOfLast { it } + 1
        if (end !in setOf(Frame.bodySize, Frame.bodySize + 1)) {
            return Execution.Rejected(WriteFault.INCOMPLETE)
        }
        if ((0 until end).any { !current.occupied[it] }) {
            return Execution.Rejected(WriteFault.INCOMPLETE)
        }
        val report = current.bytes.copyOf(end)
        val body = Frame.normalize(report) ?: return Execution.Rejected(WriteFault.BOUNDARY)
        return Execution.Ready(body)
    }

    fun expire(now: Long): Boolean {
        val current = transaction ?: return false
        if (now - current.updatedAt < timeoutMs) return false
        transaction = null
        return true
    }

    fun clear() {
        transaction = null
    }

    fun clear(connection: Connection<T>): Boolean {
        val current = transaction ?: return false
        if (current.connection != connection) return false
        transaction = null
        return true
    }

    fun poison(fault: WriteFault) {
        transaction?.fault = fault
    }

    fun active(): Boolean = transaction != null
}

internal class Inbox {
    private var report: ByteArray? = null

    fun stage(body: ByteArray): Boolean {
        if (report != null) return false
        report = body.copyOf()
        return true
    }

    fun release(): ByteArray? = report?.also { report = null }

    fun clear() {
        report = null
    }

    fun pending(): Boolean = report != null
}
