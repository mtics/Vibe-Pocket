package au.edu.uts.vibepocket.hardware.micro.subscription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateTest {
    @Test
    fun defaultsToDisabledAndKeepsClientsIsolated() {
        val store = MemoryStore()
        val state = State(store)
        state.connect("mac-a", bonded = false)
        state.connect("mac-b", bonded = false)

        assertArrayEquals(byteArrayOf(0, 0), state.value("mac-a", Channel.INPUT))
        assertTrue(state.update("mac-a", Channel.INPUT, enabled = true, bonded = false))
        assertArrayEquals(byteArrayOf(1, 0), state.value("mac-a", Channel.INPUT))
        assertArrayEquals(byteArrayOf(0, 0), state.value("mac-b", Channel.INPUT))
        assertFalse(state.update("unknown", Channel.INPUT, enabled = true, bonded = false))
    }

    @Test
    fun restoresOnlyBondedClientSubscriptions() {
        val store = MemoryStore()
        val first = State(store)
        first.connect("bonded", bonded = true)
        first.connect("guest", bonded = false)
        first.update("bonded", Channel.INPUT, enabled = true, bonded = true)
        first.update("guest", Channel.BATTERY, enabled = true, bonded = false)
        first.disconnect("bonded")
        first.disconnect("guest")

        val restored = State(store)
        restored.connect("bonded", bonded = true)
        restored.connect("guest", bonded = false)
        assertTrue(restored.enabled("bonded", Channel.INPUT))
        assertArrayEquals(byteArrayOf(1, 0), restored.value("bonded", Channel.INPUT))
        assertArrayEquals(byteArrayOf(0, 0), restored.value("guest", Channel.BATTERY))
    }

    @Test
    fun bondCompletionPersistsSubscriptionsEnabledDuringPairing() {
        val store = MemoryStore()
        val state = State(store)
        state.connect("pairing", bonded = false)
        state.update("pairing", Channel.INPUT, enabled = true, bonded = false)
        state.update("pairing", Channel.BATTERY, enabled = true, bonded = false)
        assertTrue(store.read("pairing").isEmpty())

        assertTrue(state.bonded("pairing"))
        state.disconnect("pairing")

        val restored = State(store)
        restored.connect("pairing", bonded = true)
        assertTrue(restored.enabled("pairing", Channel.INPUT))
        assertTrue(restored.enabled("pairing", Channel.BATTERY))
        assertFalse(restored.bonded("unknown"))
    }

    @Test
    fun bondRemovalClearsLiveAndPersistedSubscriptions() {
        val store = MemoryStore()
        val state = State(store)
        state.connect("mac", bonded = true)
        state.update("mac", Channel.INPUT, enabled = true, bonded = true)
        state.update("mac", Channel.BATTERY, enabled = true, bonded = true)

        assertEquals(Removal.PERSISTED, state.unbonded("mac"))
        assertFalse(state.enabled("mac", Channel.INPUT))
        assertFalse(state.enabled("mac", Channel.BATTERY))

        state.disconnect("mac")
        val restored = State(store)
        restored.connect("mac", bonded = true)
        assertFalse(restored.enabled("mac", Channel.INPUT))
        assertFalse(restored.enabled("mac", Channel.BATTERY))
    }

    @Test
    fun bondRemovalDeletesPersistedStateEvenWhenClientIsDisconnected() {
        val store = MemoryStore()
        val first = State(store)
        first.connect("mac", bonded = true)
        first.update("mac", Channel.INPUT, enabled = true, bonded = true)
        first.disconnect("mac")

        assertEquals(Removal.PERSISTED, first.unbonded("mac"))
        val restored = State(store)
        restored.connect("mac", bonded = true)
        assertFalse(restored.enabled("mac", Channel.INPUT))
    }

    @Test
    fun failedPersistentRemovalStillClearsLiveSubscriptions() {
        val store = MemoryStore(removeSucceeds = false)
        val state = State(store)
        state.connect("mac", bonded = true)
        state.update("mac", Channel.INPUT, enabled = true, bonded = true)

        assertEquals(Removal.MEMORY_ONLY, state.unbonded("mac"))
        assertFalse(state.enabled("mac", Channel.INPUT))
    }

    private class MemoryStore(private val removeSucceeds: Boolean = true) : Store {
        private val values = mutableMapOf<String, Set<Channel>>()
        override fun read(clientId: String): Set<Channel> = values[clientId].orEmpty()
        override fun write(clientId: String, channels: Set<Channel>) {
            values[clientId] = channels.toSet()
        }
        override fun remove(clientId: String): Boolean {
            if (removeSucceeds) values.remove(clientId)
            return removeSucceeds
        }
    }
}
