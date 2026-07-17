package au.edu.uts.vibepocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationSessionGateTest {
    @Test
    fun resultBeforeReleaseWaitsForTheFingerUpEvent() {
        val gate = DictationSessionGate()
        val generation = gate.begin()!!

        assertEquals(DictationGateOutcome.Pending, gate.complete(generation, "Review this."))
        assertEquals(DictationGateOutcome.Complete("Review this."), gate.release(generation))
    }

    @Test
    fun releaseBeforeResultWaitsForTheRecognizerCallback() {
        val gate = DictationSessionGate()
        val generation = gate.begin()!!

        assertEquals(DictationGateOutcome.Pending, gate.release(generation))
        assertEquals(DictationGateOutcome.Complete("Run tests."), gate.complete(generation, "Run tests."))
    }

    @Test
    fun activeOrFinalizingSessionRejectsAnotherStart() {
        val gate = DictationSessionGate()
        val generation = gate.begin()!!

        assertNull(gate.begin())
        assertEquals(DictationGateOutcome.Pending, gate.release(generation))
        assertNull(gate.begin())
        assertEquals(DictationGateOutcome.Complete(null), gate.complete(generation, null))
        assertTrue(gate.begin() != null)
    }

    @Test
    fun cancellationInvalidatesDelayedCallbacksAndDuplicateTerminals() {
        val gate = DictationSessionGate()
        val cancelled = gate.begin()!!
        gate.cancel()

        assertEquals(DictationGateOutcome.Ignored, gate.complete(cancelled, "stale"))
        val current = gate.begin()!!
        assertEquals(DictationGateOutcome.Pending, gate.release(current))
        assertEquals(DictationGateOutcome.Complete("current"), gate.complete(current, "current"))
        assertEquals(DictationGateOutcome.Ignored, gate.complete(current, "duplicate"))
    }
}
