package au.edu.uts.vibepocket.hardware.micro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTest {
    @Test
    fun connectionsAreAcceptedOnlyAfterServicesBeginAdvertising() {
        val link = Link()

        assertFalse(link.canAcceptConnection())
        link.installing()
        assertFalse(link.canAcceptConnection())
        link.advertising()
        assertTrue(link.canAcceptConnection())
        link.connected()
        assertFalse(link.canAcceptConnection())
    }

    @Test
    fun protocolGateRequiresACompletedResponseThenASubsequentRecognizedRequest() {
        val link = Link()
        link.advertising()
        link.connected()
        link.subscribed(true)
        link.mtu(66)
        repeat(2) { link.recognized() }
        link.responseCompleted()

        assertEquals(Phase.SUBSCRIBED, link.phase)
        assertFalse(link.canPulse())

        link.recognized()

        assertEquals(Phase.PROTOCOL_RESPONDING, link.phase)
        assertTrue(link.canPulse())
    }

    @Test
    fun fullReportPayloadRecoversMtuEvidenceForAnExistingConnection() {
        val link = Link()
        link.connected()
        link.subscribed(true)

        link.payload(63)

        assertEquals(66, link.mtu)
        assertTrue(link.canSend())
    }

    @Test
    fun resumedConnectionMustRenegotiateBeforeItCanControl() {
        val link = Link()

        link.resumed()

        assertEquals(Phase.CONNECTED, link.phase)
        assertFalse(link.canSend())
        assertFalse(link.canPulse())

        link.subscribed(true)
        link.mtu(66)
        link.recognized()
        link.responseCompleted()
        link.recognized()

        assertEquals(Phase.PROTOCOL_RESPONDING, link.phase)
        assertTrue(link.canPulse())
    }

    @Test
    fun disconnectClearsProtocolEvidence() {
        val link = Link()
        link.connected()
        link.subscribed(true)
        link.mtu(100)
        link.recognized()
        link.responseCompleted()
        link.recognized()

        link.disconnected()

        assertEquals(Phase.ADVERTISING, link.phase)
        assertFalse(link.canSend())
        assertFalse(link.canPulse())
    }

    @Test
    fun suspendBlocksNotificationsAndSignalsUntilExitSuspend() {
        val link = Link()
        link.connected()
        link.subscribed(true)
        link.mtu(66)
        repeat(2) { link.recognized() }
        repeat(2) { link.responseCompleted() }
        link.recognized()

        link.suspended(true)

        assertEquals(Phase.SUSPENDED, link.phase)
        assertFalse(link.canSend())
        assertFalse(link.canNotifyBattery())
        assertFalse(link.canPulse())

        link.suspended(false)

        assertEquals(Phase.PROTOCOL_RESPONDING, link.phase)
        assertTrue(link.canSend())
        assertTrue(link.canNotifyBattery())
        assertTrue(link.canPulse())
    }

    @Test
    fun nameLeaseIsClearedOnlyAfterTheOriginalNameIsObserved() {
        assertEquals(Restoration.REQUEST, restoration(true, "Xiaomi 13", Name.advertised))
        assertEquals(Restoration.CLEAR, restoration(true, "Xiaomi 13", "Xiaomi 13"))
        assertEquals(Restoration.RETAIN, restoration(true, "Xiaomi 13", "User rename"))
        assertEquals(Restoration.NOTHING, restoration(false, null, Name.advertised))
    }
}
