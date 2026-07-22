package au.edu.uts.vibepocket.hardware.micro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutdownPolicyTest {
    @Test
    fun unopenedGattReleasesClaimAndHonoursStopRequestInProcess() {
        val shutdown = ShutdownPolicy()

        assertTrue(shutdown.begin(requestStop = true))
        assertFalse(shutdown.begin(requestStop = false))
        val boundary = shutdown.complete(claimed = true, gattAttempted = false)!!

        assertTrue(boundary.releaseClaim)
        assertFalse(boundary.requestRecovery)
        assertTrue(boundary.stopService)
        assertTrue(shutdown.finished)
    }

    @Test
    fun openedGattKeepsClaimUntilRecoveryCrossesTheProcessBoundary() {
        val shutdown = ShutdownPolicy()
        shutdown.begin(requestStop = true)

        val boundary = shutdown.complete(claimed = true, gattAttempted = true)!!

        assertFalse(boundary.releaseClaim)
        assertTrue(boundary.requestRecovery)
        assertFalse(boundary.stopService)
        assertTrue(shutdown.restoreOnStop(claimed = true))
    }

    @Test
    fun recoveryIsSingleFlightAndRetriesUntilRestoreIsConfirmed() {
        val shutdown = ShutdownPolicy()

        assertEquals(RecoveryDecision.RETRY, shutdown.prepareRecovery(available = false))
        assertEquals(RecoveryDecision.REQUEST, shutdown.prepareRecovery(available = true))
        assertEquals(RecoveryDecision.BUSY, shutdown.prepareRecovery(available = true))
        assertEquals(RecoveryDecision.RETRY, shutdown.recoveryResult(ready = false))
        assertEquals(RecoveryDecision.REQUEST, shutdown.prepareRecovery(available = true))
        assertEquals(RecoveryDecision.EXIT_PROCESS, shutdown.recoveryResult(ready = true))
    }
}
