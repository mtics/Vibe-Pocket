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
    fun registrationRemainsPendingUntilTheAuthoritativeCallback() {
        val lifecycle = ProfileLifecycle()
        assertTrue(lifecycle.acceptProxy(requireNotNull(lifecycle.requestProxy())))
        val pending = requireNotNull(lifecycle.requestRegistration())

        assertTrue(lifecycle.hasRegistration())
        assertNull(lifecycle.requestRegistration())
        assertTrue(lifecycle.registrationChanged(pending, true))
        assertTrue(lifecycle.isRegistered())
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

    @Test
    fun adapterOffRejectsPendingProxyAndAllowsAFreshGeneration() {
        val lifecycle = ProfileLifecycle()
        val pending = requireNotNull(lifecycle.requestProxy())

        assertTrue(lifecycle.rejectPendingProxy())
        assertFalse(lifecycle.acceptProxy(pending))

        val replacement = requireNotNull(lifecycle.requestProxy())
        assertNotEquals(pending, replacement)
        assertTrue(lifecycle.acceptProxy(replacement))
    }

    @Test
    fun releaseWaitsForRegistrationAndProfileProxy() {
        val lifecycle = ProfileLifecycle()
        val profile = requireNotNull(lifecycle.requestProxy())
        assertTrue(lifecycle.acceptProxy(profile))
        val registration = requireNotNull(lifecycle.requestRegistration())

        assertTrue(lifecycle.hasRegistration())
        assertFalse(lifecycle.isReleased())
        assertTrue(lifecycle.registrationChanged(registration, true))
        assertFalse(lifecycle.isReleased())
        assertTrue(lifecycle.registrationChanged(registration, false))
        assertFalse(lifecycle.isReleased())
        assertTrue(lifecycle.loseProxy(profile))
        assertTrue(lifecycle.isReleased())
    }

    @Test
    fun rejectedPendingProxyIsLogicallyReleasedAndCannotRegisterLate() {
        val lifecycle = ProfileLifecycle()
        val pending = requireNotNull(lifecycle.requestProxy())

        assertFalse(lifecycle.isReleased())
        assertTrue(lifecycle.rejectPendingProxy())
        assertTrue(lifecycle.isReleased())
        assertFalse(lifecycle.acceptProxy(pending))
    }

    @Test
    fun permissionResetInvalidatesEveryPendingCallbackAndAllowsRestart() {
        val lifecycle = ProfileLifecycle()
        val profile = requireNotNull(lifecycle.requestProxy())
        assertTrue(lifecycle.acceptProxy(profile))
        val registration = requireNotNull(lifecycle.requestRegistration())

        lifecycle.reset()

        assertTrue(lifecycle.isReleased())
        assertFalse(lifecycle.registrationChanged(registration, true))
        assertFalse(lifecycle.loseProxy(profile))
        assertNotNull(lifecycle.requestProxy())
    }

    @Test
    fun activeProxyMustBeClosedBeforeClassicIsReleased() {
        val lifecycle = ProfileLifecycle()
        val profile = requireNotNull(lifecycle.requestProxy())
        assertTrue(lifecycle.acceptProxy(profile))

        assertFalse(lifecycle.isReleased())
        assertTrue(lifecycle.loseProxy(profile))
        assertTrue(lifecycle.isReleased())
    }
}
