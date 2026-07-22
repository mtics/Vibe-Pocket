package au.edu.uts.vibepocket.hardware.micro

internal enum class ConnectionDisposition {
    ACCEPTED,
    DEFERRED,
    DEFERRED_AND_CANCEL,
    REJECTED,
}

internal enum class DisconnectionDisposition {
    HOST,
    DEFERRED,
    QUARANTINED,
    IGNORED,
}

internal data class Quarantine<T>(val host: T?)

internal data class NotificationCompletion<T>(
    val packet: Packet?,
    val quarantine: Quarantine<T>? = null,
    val settledPoison: Boolean = false,
    val ignored: Boolean = false,
)

internal class HostSession<T>(
    notificationCapacity: Int = 128,
    private val resetDecoder: () -> Unit,
) {
    val link = Link()

    private val notifications = Queue(notificationCapacity)
    private val recognizedResponseTails = mutableListOf<Packet>()
    private val connected = linkedSetOf<T>()

    var generation: Long = 0
        private set

    var host: T? = null
        private set
    var deferredHost: T? = null
        private set
    var quarantinedHost: T? = null
        private set
    var pendingNotificationHost: T? = null
        private set

    fun observeConnected(value: T) {
        connected += value
    }

    fun observeDisconnected(value: T) {
        connected -= value
        if (host == value) host = null
        if (quarantinedHost == value) quarantinedHost = null
    }

    fun connect(value: T, inputSubscribed: Boolean = false): ConnectionDisposition {
        observeConnected(value)
        if (pendingNotificationHost != null) return ConnectionDisposition.REJECTED
        if (!link.canAcceptConnection()) {
            if (deferredHost != null && deferredHost != value) return ConnectionDisposition.REJECTED
            deferredHost = value
            return if (link.phase == Phase.INSTALLING) {
                ConnectionDisposition.DEFERRED
            } else {
                ConnectionDisposition.DEFERRED_AND_CANCEL
            }
        }
        if (deferredHost != null && deferredHost != value) return ConnectionDisposition.REJECTED
        if (quarantinedHost != null || host != null && host != value) {
            return ConnectionDisposition.REJECTED
        }

        deferredHost = null
        host = value
        generation += 1
        resetTransport()
        link.connected()
        link.subscribed(inputSubscribed)
        return ConnectionDisposition.ACCEPTED
    }

    fun resume(value: T, inputSubscribed: Boolean = false): Boolean {
        if (pendingNotificationHost != null || deferredHost != value || value !in connected || !link.canAcceptConnection()) {
            return false
        }
        deferredHost = null
        host = value
        generation += 1
        resetTransport()
        link.resumed()
        link.subscribed(inputSubscribed)
        return true
    }

    fun disconnect(value: T): DisconnectionDisposition {
        connected -= value
        return when (value) {
            quarantinedHost -> {
                quarantinedHost = null
                link.disconnected()
                DisconnectionDisposition.QUARANTINED
            }
            deferredHost -> DisconnectionDisposition.DEFERRED
            host -> {
                host = null
                if (notifications.inFlight()) poisonNotification(value) else resetTransport()
                link.disconnected()
                DisconnectionDisposition.HOST
            }
            else -> DisconnectionDisposition.IGNORED
        }
    }

    fun owns(value: T): Boolean = host == value

    fun isConnected(value: T): Boolean = value in connected

    fun referenced(value: T): Boolean = value in connected ||
        host == value || deferredHost == value || quarantinedHost == value

    fun teardownDevices(): Set<T> = buildSet {
        addAll(connected)
        host?.let(::add)
        quarantinedHost?.let(::add)
    }

    fun enqueueNotifications(packets: List<Packet>, recognizedResponse: Boolean = false): Quarantine<T>? {
        val recognizedTail = packets.lastOrNull().takeIf { recognizedResponse && it?.completesResponse == true }
        if (notifications.add(packets)) {
            recognizedTail?.let(recognizedResponseTails::add)
            return null
        }
        return quarantine()
    }

    fun replaceBatteryNotification(packet: Packet): Quarantine<T>? {
        require(packet.target == NotificationTarget.BATTERY)
        notifications.removeWaiting(NotificationTarget.BATTERY)
        return enqueueNotifications(listOf(packet))
    }

    fun nextNotification(batteryReady: Boolean = false): Packet? = notifications.next { packet ->
        when (packet.target) {
            NotificationTarget.INPUT -> link.canSend()
            NotificationTarget.BATTERY -> batteryReady && link.canNotifyBattery()
        }
    }

    fun completeNotification(value: T, success: Boolean): NotificationCompletion<T> {
        if (pendingNotificationHost == value) {
            val completed = notifications.complete(success = false)
            pendingNotificationHost = null
            recognizedResponseTails.clear()
            return NotificationCompletion(completed, settledPoison = true)
        }
        if (host != value) return NotificationCompletion(null, ignored = true)
        val completed = notifications.complete(success)
        val recognizedResponse = consumeRecognizedResponseTail(completed)
        if (!success) return NotificationCompletion(completed, quarantine())
        if (recognizedResponse) link.responseCompleted()
        return NotificationCompletion(completed)
    }

    fun completeNotification(success: Boolean): NotificationCompletion<T> {
        val current = host ?: return NotificationCompletion(null, ignored = true)
        return completeNotification(current, success)
    }

    fun disableSubscription(value: T, input: Boolean): Quarantine<T>? {
        if (host != value) return null
        if (input) link.subscribed(false)
        return if (notifications.inFlight()) quarantine(preserveNotification = true) else {
            resetTransport()
            null
        }
    }

    fun protocolBoundary(): Quarantine<T>? = if (notifications.inFlight()) {
        quarantine(preserveNotification = true)
    } else {
        resetTransport()
        null
    }

    fun quarantine(): Quarantine<T> = quarantine(preserveNotification = notifications.inFlight())

    fun notificationTimedOut(value: T): Boolean {
        if (pendingNotificationHost != value) return false
        pendingNotificationHost = null
        clearNotifications()
        resetDecoder()
        link.stopped()
        return true
    }

    fun clearNotifications() {
        notifications.clear()
        recognizedResponseTails.clear()
    }

    fun clearBatteryNotifications() = notifications.removeWaiting(NotificationTarget.BATTERY)

    fun stop() {
        resetTransport()
        pendingNotificationHost = null
        link.stopped()
    }

    fun finish() {
        host = null
        deferredHost = null
        quarantinedHost = null
        pendingNotificationHost = null
        connected.clear()
        clearNotifications()
        link.stopped()
    }

    private fun quarantine(preserveNotification: Boolean = false): Quarantine<T> {
        val previous = host
        if (preserveNotification && previous != null) poisonNotification(previous) else resetTransport()
        host = null
        quarantinedHost = previous
        link.stopped()
        return Quarantine(previous)
    }

    private fun resetTransport() {
        clearNotifications()
        resetDecoder()
        link.protocolBoundary()
    }

    private fun poisonNotification(value: T) {
        notifications.poisonWaiting()
        recognizedResponseTails.clear()
        pendingNotificationHost = value
        resetDecoder()
        link.protocolBoundary()
    }

    private fun consumeRecognizedResponseTail(packet: Packet?): Boolean {
        val index = recognizedResponseTails.indexOfFirst { it === packet }
        if (index < 0) return false
        recognizedResponseTails.removeAt(index)
        return true
    }
}
