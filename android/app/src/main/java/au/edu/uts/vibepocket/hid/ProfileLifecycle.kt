package au.edu.uts.vibepocket.hid

internal data class Registration(
    val profileGeneration: Long,
    val registrationGeneration: Long,
)

internal class ProfileLifecycle {
    private var profileGeneration = 0L
    private var registrationGeneration = 0L
    private var proxyPending = false
    private var proxyActive = false
    private var registrationPending = false
    private var registered = false
    private var closed = false

    fun requestProxy(): Long? {
        if (closed || proxyPending || proxyActive) return null
        profileGeneration += 1
        registrationGeneration = 0
        proxyPending = true
        return profileGeneration
    }

    fun acceptProxy(generation: Long): Boolean {
        if (closed || generation != profileGeneration || !proxyPending) return false
        proxyPending = false
        proxyActive = true
        return true
    }

    fun rejectProxy(generation: Long): Boolean {
        if (closed || generation != profileGeneration || !proxyPending) return false
        proxyPending = false
        return true
    }

    fun rejectPendingProxy(): Boolean {
        if (closed || !proxyPending) return false
        profileGeneration += 1
        registrationGeneration = 0
        proxyPending = false
        return true
    }

    fun requestRegistration(): Registration? {
        if (closed || !proxyActive || registrationPending || registered) return null
        registrationGeneration += 1
        registrationPending = true
        return Registration(profileGeneration, registrationGeneration)
    }

    fun rejectRegistration(token: Registration): Boolean {
        if (!matches(token) || !registrationPending) return false
        registrationPending = false
        registrationGeneration += 1
        return true
    }

    fun registrationChanged(token: Registration, value: Boolean): Boolean {
        if (!matches(token)) return false
        registrationPending = false
        registered = value
        return true
    }

    fun loseProxy(generation: Long): Boolean {
        if (closed || generation != profileGeneration || !proxyActive) return false
        profileGeneration += 1
        registrationGeneration = 0
        proxyPending = false
        proxyActive = false
        registrationPending = false
        registered = false
        return true
    }

    fun isCurrent(generation: Long): Boolean =
        !closed && proxyActive && generation == profileGeneration

    fun isCurrent(token: Registration): Boolean = matches(token)

    fun currentGeneration(): Long? = profileGeneration.takeIf { !closed && proxyActive }

    fun isRegistered(): Boolean = registered

    fun close() {
        closed = true
        profileGeneration += 1
        registrationGeneration = 0
        proxyPending = false
        proxyActive = false
        registrationPending = false
        registered = false
    }

    private fun matches(token: Registration): Boolean =
        !closed &&
            proxyActive &&
            token.profileGeneration == profileGeneration &&
            token.registrationGeneration == registrationGeneration
}
