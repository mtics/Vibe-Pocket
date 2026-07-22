package au.edu.uts.vibepocket.hardware.micro

internal class Teardown<T> {
    private val pending = mutableSetOf<T>()
    private var active = false

    fun begin(values: Collection<T>) {
        check(!active) { "Micro teardown is already active." }
        active = true
        pending.clear()
        pending.addAll(values)
    }

    fun connected(value: T): Boolean {
        if (!active) return false
        pending += value
        return true
    }

    fun disconnected(value: T): Boolean {
        if (!active || !pending.remove(value)) return false
        return pending.isEmpty()
    }

    fun abandon(): Int {
        if (!active) return 0
        val count = pending.size
        active = false
        pending.clear()
        return count
    }

    fun finish() {
        active = false
        pending.clear()
    }

    fun ready(): Boolean = active && pending.isEmpty()

    fun pending(): Int = pending.size
}
