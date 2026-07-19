package au.edu.uts.vibepocket.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectTest {
    @Test
    fun reconnectsOnlyTheExplicitlyRememberedPairedHost() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            preferred(
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
            preferred(
                registered = true,
                connectedAddress = "AA:BB:CC:DD:EE:FF",
                connectingAddress = null,
                preferredAddress = "AA:BB:CC:DD:EE:FF",
                bondedAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            ),
        )
        assertNull(
            preferred(
                registered = true,
                connectedAddress = null,
                connectingAddress = "AA:BB:CC:DD:EE:FF",
                preferredAddress = "AA:BB:CC:DD:EE:FF",
                bondedAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            ),
        )
    }

    @Test
    fun reconnectRetriesUseBoundedBackoff() {
        assertEquals(500L, delay(0))
        assertEquals(1_200L, delay(1))
        assertEquals(2_500L, delay(2))
        assertNull(delay(3))
    }

    @Test
    fun infersOnlyOneAlreadyPairedComputerWhenNoHostWasSelected() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            infer(
                registered = true,
                connectedAddress = null,
                connectingAddress = null,
                preferredAddress = null,
                bondedAddresses = setOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"),
                computerAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            ),
        )
    }

    @Test
    fun doesNotGuessWhenSeveralPairedComputersExist() {
        assertNull(
            infer(
                registered = true,
                connectedAddress = null,
                connectingAddress = null,
                preferredAddress = null,
                bondedAddresses = setOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"),
                computerAddresses = setOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"),
            ),
        )
    }

    @Test
    fun newlyBondedComputerContinuesDirectlyIntoHidConnection() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            newlyBondedComputer("AA:BB:CC:DD:EE:FF", bonded = true, computer = true),
        )
        assertNull(newlyBondedComputer("AA:BB:CC:DD:EE:FF", bonded = false, computer = true))
        assertNull(newlyBondedComputer("AA:BB:CC:DD:EE:FF", bonded = true, computer = false))
    }
}
