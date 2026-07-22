package au.edu.uts.vibepocket.hardware.micro

enum class Phase {
    STOPPED,
    INSTALLING,
    ADVERTISING,
    CONNECTED,
    SUBSCRIBED,
    PROTOCOL_RESPONDING,
    SUSPENDED,
}

class Link {
    var phase: Phase = Phase.STOPPED
        private set
    var mtu: Int = 23
        private set

    private var connected = false
    private var subscribed = false
    private var recognized = 0
    private var completedResponses = 0
    private var recognizedAfterResponse = false
    private var validated = false
    private var suspended = false

    fun installing() = set(Phase.INSTALLING)

    fun advertising() = set(Phase.ADVERTISING)

    fun connected() {
        resetConnection()
        update()
    }

    fun resumed() {
        resetConnection()
        update()
    }

    fun mtu(value: Int) {
        mtu = value
        update()
    }

    fun payload(size: Int) {
        mtu = maxOf(mtu, size + attHeaderBytes)
        update()
    }

    fun subscribed(enabled: Boolean) {
        subscribed = enabled
        update()
    }

    fun suspended(value: Boolean) {
        suspended = value
        update()
    }

    fun recognized() {
        recognized += 1
        if (completedResponses > 0) recognizedAfterResponse = true
        update()
    }

    fun responseCompleted() {
        if (completedResponses < recognized) completedResponses += 1
        update()
    }

    fun protocolBoundary() {
        recognized = 0
        completedResponses = 0
        recognizedAfterResponse = false
        validated = false
        update()
    }

    fun disconnected() {
        connected = false
        subscribed = false
        recognized = 0
        completedResponses = 0
        recognizedAfterResponse = false
        validated = false
        suspended = false
        mtu = 23
        set(Phase.ADVERTISING)
    }

    fun stopped() {
        connected = false
        subscribed = false
        recognized = 0
        completedResponses = 0
        recognizedAfterResponse = false
        validated = false
        suspended = false
        mtu = 23
        set(Phase.STOPPED)
    }

    fun canSend(): Boolean = connected && subscribed && mtu >= minimumMtu && !suspended

    fun canNotifyBattery(): Boolean = connected && !suspended

    fun canPulse(): Boolean = phase == Phase.PROTOCOL_RESPONDING && !suspended

    fun canAcceptConnection(): Boolean = phase == Phase.ADVERTISING

    private fun update() {
        if (subscribed && mtu >= minimumMtu && completedResponses >= 1 && recognizedAfterResponse) {
            validated = true
        }
        val next = when {
            !connected -> phase
            suspended -> Phase.SUSPENDED
            subscribed && mtu >= minimumMtu && validated -> Phase.PROTOCOL_RESPONDING
            subscribed -> Phase.SUBSCRIBED
            else -> Phase.CONNECTED
        }
        set(next)
    }

    private fun set(value: Phase) {
        phase = value
    }

    private fun resetConnection() {
        connected = true
        subscribed = false
        recognized = 0
        completedResponses = 0
        recognizedAfterResponse = false
        validated = false
        suspended = false
        mtu = 23
    }

    companion object {
        const val minimumMtu = 66
        private const val attHeaderBytes = 3
    }
}
