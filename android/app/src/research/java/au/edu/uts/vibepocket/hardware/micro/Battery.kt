package au.edu.uts.vibepocket.hardware.micro

data class BatteryState(
    val level: Int,
    val charging: Boolean,
) {
    fun gattValue(): ByteArray = byteArrayOf(level.coerceIn(0, 100).toByte())

    companion object {
        val unknown = BatteryState(level = 0, charging = false)
    }
}

internal fun batteryState(level: Int, scale: Int, charging: Boolean): BatteryState {
    if (level < 0 || scale <= 0) return BatteryState.unknown
    val percentage = ((level.toLong() * 100L) / scale).coerceIn(0L, 100L).toInt()
    return BatteryState(percentage, charging)
}

internal data class BatteryNotification<T>(
    val host: T,
    val state: BatteryState,
)

internal class BatteryPolicy<T>(initial: BatteryState = BatteryState.unknown) {
    var state: BatteryState = initial
        private set

    fun sample(
        sampled: BatteryState,
        host: T?,
        bonded: Boolean,
        subscribed: Boolean,
    ): BatteryNotification<T>? {
        val levelChanged = sampled.level != state.level
        state = sampled
        return if (levelChanged && host != null && bonded && subscribed) {
            BatteryNotification(host, sampled)
        } else {
            null
        }
    }
}
