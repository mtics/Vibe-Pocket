package au.edu.uts.vibepocket.hardware.micro.subscription

internal enum class Channel {
    INPUT,
    BATTERY,
}

internal enum class Removal {
    PERSISTED,
    MEMORY_ONLY,
}

internal interface Store {
    fun read(clientId: String): Set<Channel>
    fun write(clientId: String, channels: Set<Channel>)
    fun remove(clientId: String): Boolean
}

internal class State(private val store: Store) {
    private data class Client(
        var bonded: Boolean,
        val enabled: MutableSet<Channel>,
    )

    private val clients = mutableMapOf<String, Client>()

    fun connect(clientId: String, bonded: Boolean) {
        clients[clientId] = Client(
            bonded = bonded,
            enabled = if (bonded) store.read(clientId).toMutableSet() else mutableSetOf(),
        )
    }

    fun disconnect(clientId: String) {
        clients.remove(clientId)
    }

    fun value(clientId: String, channel: Channel): ByteArray =
        if (clients[clientId]?.enabled?.contains(channel) == true) enabledValue else disabledValue

    fun enabled(clientId: String, channel: Channel): Boolean =
        clients[clientId]?.enabled?.contains(channel) == true

    fun bonded(clientId: String): Boolean {
        val client = clients[clientId] ?: return false
        client.bonded = true
        store.write(clientId, client.enabled)
        return true
    }

    fun unbonded(clientId: String): Removal {
        val client = clients[clientId]
        client?.bonded = false
        client?.enabled?.clear()
        return if (store.remove(clientId)) Removal.PERSISTED else Removal.MEMORY_ONLY
    }

    fun update(clientId: String, channel: Channel, enabled: Boolean, bonded: Boolean): Boolean {
        val client = clients[clientId] ?: return false
        client.bonded = client.bonded || bonded
        if (enabled) client.enabled.add(channel) else client.enabled.remove(channel)
        if (client.bonded) store.write(clientId, client.enabled)
        return true
    }

    private companion object {
        val enabledValue = byteArrayOf(0x01, 0x00)
        val disabledValue = byteArrayOf(0x00, 0x00)
    }
}
