package au.edu.uts.vibepocket.hardware.micro

enum class NotificationTarget {
    INPUT,
    BATTERY,
}

data class Packet(
    val bytes: ByteArray,
    val completesResponse: Boolean = false,
    val target: NotificationTarget = NotificationTarget.INPUT,
)

class Queue(private val capacity: Int = 128) {
    private val waiting = ArrayDeque<Packet>()
    private var current: Packet? = null

    init {
        require(capacity > 0) { "The Micro notification queue capacity must be positive." }
    }

    fun add(packets: List<Packet>): Boolean {
        if (packets.isEmpty()) return true
        val occupied = waiting.size + if (current == null) 0 else 1
        if (packets.size > capacity - occupied) return false
        waiting.addAll(packets)
        return true
    }

    fun next(ready: (Packet) -> Boolean = { true }): Packet? {
        if (current != null) return null
        val next = waiting.firstOrNull(ready) ?: return null
        waiting.remove(next)
        current = next
        return next
    }

    fun removeWaiting(target: NotificationTarget) {
        waiting.removeAll { it.target == target }
    }

    fun poisonWaiting() {
        waiting.clear()
    }

    fun complete(success: Boolean): Packet? {
        val completed = current
        current = null
        if (!success) waiting.clear()
        return completed
    }

    fun clear() {
        current = null
        waiting.clear()
    }

    fun idle(): Boolean = current == null && waiting.isEmpty()

    fun inFlight(): Boolean = current != null
}
