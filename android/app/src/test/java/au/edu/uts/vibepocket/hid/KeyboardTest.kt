package au.edu.uts.vibepocket.hid

import au.edu.uts.vibepocket.hardware.Handover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTest {
    @Test
    fun onlyConfirmedFinalReleasePermitsMicroHandover() {
        assertEquals(Handover.READY, handover(QueueResult.Completed(Unit)))
        assertEquals(Handover.FAILED, handover(QueueResult.Failed))
        assertEquals(Handover.FAILED, handover(QueueResult.TimedOut))
        assertEquals(Handover.FAILED, handover(QueueResult.Cancelled))
        assertEquals(Handover.FAILED, handover(QueueResult.Rejected))
    }

    @Test
    fun failedFinalReleaseQuarantinesClassicBeforeReportingFailure() {
        var quarantined = false

        val outcome = secureHandover(QueueResult.Failed) {
            quarantined = true
        }

        assertTrue(quarantined)
        assertEquals(Handover.FAILED, outcome)
    }

    @Test
    fun confirmedFinalReleaseDoesNotQuarantineClassic() {
        var quarantined = false

        val outcome = secureHandover(QueueResult.Completed(Unit)) {
            quarantined = true
        }

        assertFalse(quarantined)
        assertEquals(Handover.READY, outcome)
    }

    @Test
    fun uncertainReportStateRequiresExplicitConfirmationBeforeReuse() {
        val settlement = ReportSettlement()

        settlement.begin()

        assertFalse(settlement.reusable())
        settlement.confirm()
        assertTrue(settlement.reusable())
    }
}
