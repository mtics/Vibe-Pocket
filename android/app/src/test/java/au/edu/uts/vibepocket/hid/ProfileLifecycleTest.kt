package au.edu.uts.vibepocket.hid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLifecycleTest {
    @Test
    fun proxyAndRegistrationRequestsAreSingleFlight() {
        val lifecycle = ProfileLifecycle()
        val profile = requireNotNull(lifecycle.requestProxy())

        assertNull(lifecycle.requestProxy())
        assertTrue(lifecycle.acceptProxy(profile))
        assertNull(lifecycle.requestProxy())

        val registration = requireNotNull(lifecycle.requestRegistration())
        assertNull(lifecycle.requestRegistration())
        assertTrue(lifecycle.registrationChanged(registration, true))
        assertTrue(lifecycle.isRegistered())
        assertNull(lifecycle.requestRegistration())
    }

    @Test
    fun staleCallbacksCannotMutateAReplacementProfile() {
        val lifecycle = ProfileLifecycle()
        val oldProfile = requireNotNull(lifecycle.requestProxy())
        assertTrue(lifecycle.acceptProxy(oldProfile))
        val oldRegistration = requireNotNull(lifecycle.requestRegistration())
        assertTrue(lifecycle.registrationChanged(oldRegistration, true))
        assertTrue(lifecycle.loseProxy(oldProfile))

        val newProfile = requireNotNull(lifecycle.requestProxy())
        assertNotEquals(oldProfile, newProfile)
        assertTrue(lifecycle.acceptProxy(newProfile))
        val newRegistration = requireNotNull(lifecycle.requestRegistration())

        assertFalse(lifecycle.registrationChanged(oldRegistration, false))
        assertFalse(lifecycle.loseProxy(oldProfile))
        assertTrue(lifecycle.registrationChanged(newRegistration, true))
        assertTrue(lifecycle.isRegistered())
    }

    @Test
    fun rejectedRegistrationInvalidatesItsLateCallbackAndCanRetry() {
        val lifecycle = ProfileLifecycle()
        assertTrue(lifecycle.acceptProxy(requireNotNull(lifecycle.requestProxy())))
        val rejected = requireNotNull(lifecycle.requestRegistration())

        assertTrue(lifecycle.rejectRegistration(rejected))
        assertFalse(lifecycle.registrationChanged(rejected, true))
        assertNotNull(lifecycle.requestRegistration())
    }

    @Test
    fun autoUnregisterCanBeRegisteredAgainOnResume() {
        val lifecycle = ProfileLifecycle()
        assertTrue(lifecycle.acceptProxy(requireNotNull(lifecycle.requestProxy())))
        val first = requireNotNull(lifecycle.requestRegistration())
        assertTrue(lifecycle.registrationChanged(first, true))

        assertTrue(lifecycle.registrationChanged(first, false))
        val resumed = requireNotNull(lifecycle.requestRegistration())

        assertFalse(lifecycle.registrationChanged(first, true))
        assertTrue(lifecycle.registrationChanged(resumed, true))
        assertTrue(lifecycle.isRegistered())
    }

    @Test
    fun closeInvalidatesAProxyThatArrivesLate() {
        val lifecycle = ProfileLifecycle()
        val pending = requireNotNull(lifecycle.requestProxy())

        lifecycle.close()

        assertFalse(lifecycle.acceptProxy(pending))
        assertNull(lifecycle.requestProxy())
    }
}
