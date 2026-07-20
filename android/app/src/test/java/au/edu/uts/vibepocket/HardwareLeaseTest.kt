package au.edu.uts.vibepocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HardwareLeaseTest {
    @Test
    fun retainsTheSameResourceAndClosesItExactlyOnce() {
        var closes = 0
        val resource = AutoCloseable { closes += 1 }
        val lease = HardwareLease(resource)

        assertSame(resource, lease.value)
        lease.close()
        lease.close()

        assertEquals(1, closes)
    }
}
