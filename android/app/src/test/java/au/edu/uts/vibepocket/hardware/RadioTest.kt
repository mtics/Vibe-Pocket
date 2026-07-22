package au.edu.uts.vibepocket.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTest {
    private class Classic {
        var paused = false
        var resumed = 0
        var ready: ((Handover) -> Unit)? = null
    }

    private fun radio() = Radio<Classic>(
        pause = { classic, ready ->
            classic.paused = true
            classic.ready = ready
        },
        resume = { classic ->
            classic.paused = false
            classic.resumed += 1
        },
    )

    @Test
    fun claimWaitsForClassicReleaseAndRestoresIt() {
        val radio = radio()
        val classic = Classic()
        val owner = Any()
        var ready = false
        radio.attach(classic)

        assertTrue(radio.claim(owner) { ready = true })
        assertTrue(classic.paused)
        assertFalse(ready)

        classic.ready?.invoke(Handover.READY)
        assertTrue(ready)

        radio.release(owner)
        assertFalse(classic.paused)
        assertEquals(1, classic.resumed)
    }

    @Test
    fun competingClaimIsRejected() {
        val radio = radio()
        val first = Any()
        val second = Any()

        assertTrue(radio.claim(first) {})
        assertFalse(radio.claim(second) {})
    }

    @Test
    fun stalePauseCallbackCannotStartReleasedOwner() {
        val radio = radio()
        val classic = Classic()
        val owner = Any()
        var ready = false
        radio.attach(classic)
        radio.claim(owner) { ready = true }

        radio.release(owner)
        assertTrue(classic.paused)
        assertEquals(0, classic.resumed)
        classic.ready?.invoke(Handover.READY)

        assertFalse(ready)
        assertFalse(classic.paused)
        assertEquals(1, classic.resumed)
    }

    @Test(expected = IllegalStateException::class)
    fun classicCannotAttachDuringClaim() {
        val radio = radio()
        assertTrue(radio.claim(Any()) {})
        radio.attach(Classic())
    }

    @Test
    fun aNewClaimCannotInterleaveWithClassicRestoration() {
        lateinit var radio: Radio<Classic>
        var interleaved = true
        val classic = Classic()
        val owner = Any()
        radio = Radio(
            pause = { target, ready ->
                target.paused = true
                target.ready = ready
            },
            resume = { target ->
                interleaved = radio.claim(Any()) {}
                target.paused = false
            },
        )
        radio.attach(classic)
        assertTrue(radio.claim(owner) {})
        classic.ready?.invoke(Handover.READY)

        radio.release(owner)

        assertFalse(interleaved)
        assertFalse(classic.paused)
    }

    @Test
    fun failedPauseRestoresClassicWithoutStartingMicro() {
        val radio = radio()
        val classic = Classic()
        val owner = Any()
        var ready = false
        radio.attach(classic)

        assertTrue(radio.claim(owner) { ready = true })
        classic.ready?.invoke(Handover.FAILED)

        assertFalse(ready)
        assertFalse(classic.paused)
        assertEquals(1, classic.resumed)
        assertTrue(radio.claim(Any()) {})
    }

    @Test
    fun failedPauseAfterReleaseRestoresClassicExactlyOnce() {
        val radio = radio()
        val classic = Classic()
        val owner = Any()
        var ready = false
        radio.attach(classic)
        radio.claim(owner) { ready = true }

        radio.release(owner)
        classic.ready?.invoke(Handover.FAILED)
        classic.ready?.invoke(Handover.FAILED)

        assertFalse(ready)
        assertFalse(classic.paused)
        assertEquals(1, classic.resumed)
    }
}
