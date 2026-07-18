package au.edu.uts.vibepocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HidReconnectPolicyTest {
    @Test
    fun reconnectsOnlyTheExplicitlyRememberedPairedHost() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            preferredHostToReconnect(
                registered = true,
                connectedAddress = null,
                connectingAddress = null,
                preferredAddress = "AA:BB:CC:DD:EE:FF",
                bondedAddresses = setOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"),
            ),
        )
    }

    @Test
    fun doesNotConnectWhileAHostIsAlreadyConnectedOrConnecting() {
        assertNull(
            preferredHostToReconnect(
                registered = true,
                connectedAddress = "AA:BB:CC:DD:EE:FF",
                connectingAddress = null,
                preferredAddress = "AA:BB:CC:DD:EE:FF",
                bondedAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            ),
        )
        assertNull(
            preferredHostToReconnect(
                registered = true,
                connectedAddress = null,
                connectingAddress = "AA:BB:CC:DD:EE:FF",
                preferredAddress = "AA:BB:CC:DD:EE:FF",
                bondedAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            ),
        )
    }
}
