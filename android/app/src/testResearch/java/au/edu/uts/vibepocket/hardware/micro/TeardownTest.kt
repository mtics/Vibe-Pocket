package au.edu.uts.vibepocket.hardware.micro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeardownTest {
    @Test
    fun restorationWaitsForEveryTrackedDisconnect() {
        val teardown = Teardown<String>()

        teardown.begin(listOf("host", "quarantine"))
        assertFalse(teardown.ready())
        assertEquals(2, teardown.pending())
        assertFalse(teardown.disconnected("host"))
        assertEquals(1, teardown.pending())
        assertTrue(teardown.disconnected("quarantine"))
    }

    @Test
    fun connectionArrivingDuringShutdownJoinsTheBarrier() {
        val teardown = Teardown<String>()

        teardown.begin(listOf("first"))
        assertTrue(teardown.connected("late"))
        assertFalse(teardown.disconnected("first"))
        assertTrue(teardown.disconnected("late"))
    }

    @Test
    fun unrelatedGlobalDeviceCannotCompleteTheLocalBarrier() {
        val teardown = Teardown<String>()

        teardown.begin(listOf("host"))
        assertEquals(1, teardown.pending())
        assertFalse(teardown.disconnected("unknown"))
        assertEquals(1, teardown.pending())
    }

    @Test
    fun noConnectionCanFinishImmediately() {
        val teardown = Teardown<String>()
        teardown.begin(emptyList())
        assertTrue(teardown.ready())
    }

    @Test
    fun queuedLocalConnectionCanJoinAnEmptyBarrierBeforeFinish() {
        val teardown = Teardown<String>()
        teardown.begin(emptyList())

        assertTrue(teardown.ready())
        assertTrue(teardown.connected("queued"))
        assertFalse(teardown.ready())
        assertTrue(teardown.disconnected("queued"))
        assertTrue(teardown.ready())
    }

    @Test
    fun realReconnectReopensAConfirmedDisconnect() {
        val teardown = Teardown<String>()
        teardown.begin(listOf("host"))

        assertTrue(teardown.disconnected("host"))
        assertTrue(teardown.connected("host"))
        assertFalse(teardown.ready())
    }

    @Test
    fun timeoutAbandonsOnlyTheLocalBarrierForProcessBoundaryCleanup() {
        val teardown = Teardown<String>()
        teardown.begin(listOf("host", "quarantine"))

        assertEquals(2, teardown.abandon())
        assertEquals(0, teardown.pending())
        assertFalse(teardown.ready())
        assertFalse(teardown.connected("late"))
        assertFalse(teardown.disconnected("host"))
    }
}
