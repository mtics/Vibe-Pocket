package au.edu.uts.vibepocket.hardware.micro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSessionTest {
    @Test
    fun acceptsOneHostAndPromotesTheDeferredHostAfterDisconnect() {
        val session = HostSession<String>(resetDecoder = {})
        session.link.advertising()

        assertEquals(ConnectionDisposition.ACCEPTED, session.connect("first"))
        assertEquals(ConnectionDisposition.DEFERRED_AND_CANCEL, session.connect("second"))
        assertTrue(session.owns("first"))
        assertEquals("second", session.deferredHost)

        assertEquals(DisconnectionDisposition.DEFERRED, session.disconnect("second"))
        assertEquals(DisconnectionDisposition.HOST, session.disconnect("first"))
        assertFalse(session.resume("second"))

        assertEquals(ConnectionDisposition.ACCEPTED, session.connect("second"))
        assertTrue(session.owns("second"))
    }

    @Test
    fun connectionDuringInstallationIsDeferredAndResumedWhenStillConnected() {
        val session = HostSession<String>(resetDecoder = {})
        session.link.installing()

        assertEquals(ConnectionDisposition.DEFERRED, session.connect("host"))
        session.link.advertising()

        assertTrue(session.resume("host"))
        assertTrue(session.owns("host"))
    }

    @Test
    fun firstInstallClaimantIsStableAndLaterConnectionsAreRejected() {
        val session = HostSession<String>(resetDecoder = {})
        session.link.installing()

        assertEquals(ConnectionDisposition.DEFERRED, session.connect("first"))
        assertEquals(ConnectionDisposition.REJECTED, session.connect("second"))
        assertEquals("first", session.deferredHost)

        session.link.advertising()
        assertEquals(ConnectionDisposition.REJECTED, session.connect("third"))
        assertEquals("first", session.deferredHost)
        assertTrue(session.resume("first"))
        assertTrue(session.owns("first"))
    }

    @Test
    fun activeHostKeepsOneDeterministicDeferredSuccessor() {
        val session = HostSession<String>(resetDecoder = {})
        session.link.advertising()
        session.connect("active")

        assertEquals(ConnectionDisposition.DEFERRED_AND_CANCEL, session.connect("first-waiter"))
        assertEquals(ConnectionDisposition.REJECTED, session.connect("later-waiter"))
        assertEquals("first-waiter", session.deferredHost)
    }

    @Test
    fun restoredInputSubscriptionHydratesAcceptedAndResumedLinks() {
        val accepted = HostSession<String>(resetDecoder = {})
        accepted.link.advertising()
        accepted.connect("accepted", inputSubscribed = true)
        accepted.link.mtu(Link.minimumMtu)

        assertTrue(accepted.link.canSend())

        val resumed = HostSession<String>(resetDecoder = {})
        resumed.link.installing()
        resumed.connect("resumed")
        resumed.link.advertising()
        resumed.resume("resumed", inputSubscribed = true)
        resumed.link.mtu(Link.minimumMtu)

        assertTrue(resumed.link.canSend())
    }

    @Test
    fun uncertainNotificationQuarantinesHostClearsQueueAndResetsDecoder() {
        var resets = 0
        val session = HostSession<String>(resetDecoder = { resets += 1 })
        session.link.advertising()
        session.connect("host", inputSubscribed = true)
        session.link.mtu(Link.minimumMtu)
        val first = Packet(byteArrayOf(1))
        val second = Packet(byteArrayOf(2), completesResponse = true)
        assertNull(session.enqueueNotifications(listOf(first, second), recognizedResponse = true))
        assertEquals(first, session.nextNotification())

        val completion = session.completeNotification(success = false)

        assertEquals(first, completion.packet)
        assertEquals("host", completion.quarantine?.host)
        assertEquals("host", session.quarantinedHost)
        assertNull(session.host)
        assertNull(session.nextNotification())
        assertEquals(2, resets)
        assertEquals(Phase.STOPPED, session.link.phase)
    }

    @Test
    fun firstDeliveredResponseThenASubsequentRecognizedRequestEnablesControl() {
        val session = HostSession<String>(resetDecoder = {})
        session.link.advertising()
        session.connect("host", inputSubscribed = true)
        session.link.mtu(Link.minimumMtu)
        session.link.recognized()
        val firstTail = Packet(byteArrayOf(1), completesResponse = true)
        val secondTail = Packet(byteArrayOf(2), completesResponse = true)
        assertNull(session.enqueueNotifications(listOf(firstTail), recognizedResponse = true))
        assertNull(session.enqueueNotifications(listOf(secondTail), recognizedResponse = true))

        assertEquals(firstTail, session.nextNotification())
        session.completeNotification(success = true)

        assertEquals(Phase.SUBSCRIBED, session.link.phase)
        assertFalse(session.link.canPulse())

        session.link.recognized()

        assertEquals(Phase.PROTOCOL_RESPONDING, session.link.phase)
        assertTrue(session.link.canPulse())

        assertEquals(secondTail, session.nextNotification())
        session.completeNotification(success = true)

        assertEquals(Phase.PROTOCOL_RESPONDING, session.link.phase)
        assertTrue(session.link.canPulse())
    }

    @Test
    fun overflowQuarantinesWithoutExceedingTheBoundedQueue() {
        val session = HostSession<String>(notificationCapacity = 1, resetDecoder = {})
        session.link.advertising()
        session.connect("host", inputSubscribed = true)
        session.link.mtu(Link.minimumMtu)
        assertNull(session.enqueueNotifications(listOf(Packet(byteArrayOf(1)))))

        val quarantine = session.enqueueNotifications(listOf(Packet(byteArrayOf(2))))

        assertEquals("host", quarantine?.host)
        assertNull(session.nextNotification())
    }

    @Test
    fun batteryNotificationsUseTheSameSingleFlightQueueWithoutInputSubscription() {
        val session = HostSession<String>(resetDecoder = {})
        session.link.advertising()
        session.connect("host", inputSubscribed = false)
        val battery = Packet(byteArrayOf(81), target = NotificationTarget.BATTERY)
        assertNull(session.enqueueNotifications(listOf(battery)))

        assertNull(session.nextNotification(batteryReady = false))
        assertEquals(battery, session.nextNotification(batteryReady = true))
        assertNull(session.nextNotification(batteryReady = true))
    }

    @Test
    fun latestWaitingBatteryLevelReplacesTheStaleOne() {
        val session = HostSession<String>(resetDecoder = {})
        session.link.advertising()
        session.connect("host")
        val stale = Packet(byteArrayOf(80), target = NotificationTarget.BATTERY)
        val latest = Packet(byteArrayOf(81), target = NotificationTarget.BATTERY)

        assertNull(session.replaceBatteryNotification(stale))
        assertNull(session.replaceBatteryNotification(latest))

        assertEquals(latest, session.nextNotification(batteryReady = true))
    }

    @Test
    fun transportBoundariesResetDecoder() {
        var resets = 0
        val session = HostSession<String>(resetDecoder = { resets += 1 })
        session.link.advertising()
        session.connect("host")
        session.disconnect("host")
        session.stop()

        assertEquals(3, resets)
    }

    @Test
    fun disconnectWithNotificationInFlightPoisonsUntilTheOldCallbackArrives() {
        var resets = 0
        val session = HostSession<String>(resetDecoder = { resets += 1 })
        session.link.advertising()
        session.connect("host", inputSubscribed = true)
        session.link.mtu(Link.minimumMtu)
        val packet = Packet(byteArrayOf(1))
        session.enqueueNotifications(listOf(packet))
        assertEquals(packet, session.nextNotification())

        assertEquals(DisconnectionDisposition.HOST, session.disconnect("host"))
        session.link.advertising()
        assertEquals(ConnectionDisposition.REJECTED, session.connect("host"))
        assertTrue(session.completeNotification("host", success = true).settledPoison)

        assertEquals(ConnectionDisposition.ACCEPTED, session.connect("host"))
        assertTrue(resets >= 3)
    }

    @Test
    fun notificationTimeoutEndsThePoisonAtARebuildBoundary() {
        val session = HostSession<String>(resetDecoder = {})
        session.link.advertising()
        session.connect("host", inputSubscribed = true)
        session.link.mtu(Link.minimumMtu)
        session.enqueueNotifications(listOf(Packet(byteArrayOf(1))))
        session.nextNotification()
        session.disconnect("host")

        assertTrue(session.notificationTimedOut("host"))
        assertNull(session.pendingNotificationHost)
        assertFalse(session.notificationTimedOut("host"))
    }
}
