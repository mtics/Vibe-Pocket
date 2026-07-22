package au.edu.uts.vibepocket.hardware.micro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueTest {
    @Test
    fun queueIsSingleFlightAndStopsAfterAnUncertainOutcome() {
        val queue = Queue()
        val first = Packet(byteArrayOf(1))
        val second = Packet(byteArrayOf(2), completesResponse = true)
        queue.add(listOf(first, second))

        assertEquals(first, queue.next())
        assertNull(queue.next())
        assertEquals(first, queue.complete(success = true))
        assertEquals(second, queue.next())
        assertEquals(second, queue.complete(success = false))
        assertTrue(queue.idle())
    }

    @Test
    fun queueRejectsOverflowWithoutDiscardingAcceptedPackets() {
        val queue = Queue(capacity = 2)
        val first = Packet(byteArrayOf(1))
        val second = Packet(byteArrayOf(2))
        val overflow = Packet(byteArrayOf(3))

        assertTrue(queue.add(listOf(first, second)))
        assertFalse(queue.add(listOf(overflow)))
        assertEquals(first, queue.next())
        assertEquals(first, queue.complete(success = true))
        assertEquals(second, queue.next())
    }

    @Test
    fun readyNotificationCanBypassAChannelThatIsNotReady() {
        val queue = Queue()
        val input = Packet(byteArrayOf(1))
        val battery = Packet(byteArrayOf(80), target = NotificationTarget.BATTERY)
        queue.add(listOf(input, battery))

        assertEquals(battery, queue.next { it.target == NotificationTarget.BATTERY })
        assertEquals(battery, queue.complete(success = true))
        assertEquals(input, queue.next())
    }
}
