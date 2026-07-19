package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.bridge.Failure
import au.edu.uts.vibepocket.connection.Claim
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.Invitation
import au.edu.uts.vibepocket.connection.LoadOutcome
import au.edu.uts.vibepocket.connection.Store
import au.edu.uts.vibepocket.connection.valueOrNull
import au.edu.uts.vibepocket.control.Snapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

internal class Connection(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val client: Client,
    private val store: Store,
    private val pending: Pending,
    private val current: () -> Config?,
    private val publishPending: () -> Unit,
    private val connected: (Config, Snapshot) -> Unit,
    private val disconnected: () -> Unit,
    private val rejected: (Throwable) -> Unit,
    private val recover: (Config) -> Unit,
    private val nonce: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val retry: Retry = Retry(),
) {
    private data class Attempt(
        val generation: Long,
        val id: String,
        val claim: Claim?,
    )

    private val lock = Any()
    private var generation = 0L
    private var claim = store.loadClaimRecord().valueOrNull()
    private var active: Attempt? = null
    private var revoking = false

    val pendingInvitation: Invitation?
        get() = synchronized(lock) { claim?.invitation }

    fun restore() {
        retryRevocation()
    }

    fun connect(baseUrl: String, credential: String): Boolean {
        val config = runCatching { Config(baseUrl.trim(), credential.trim()) }
            .getOrElse {
                rejected(it)
                return false
            }
        val previous = current()
        if (previous?.normalizedUrl == config.normalizedUrl && previous.credential == config.credential) return false
        return start { config }
    }

    fun offer(invitation: Invitation): Boolean {
        val superseded = runCatching {
            synchronized(lock) {
                val previous = claim ?: return@synchronized null
                if (previous.invitation == invitation) return@synchronized null
                store.clearClaim()
                claim = null
                active?.takeIf { it.claim != null }?.also {
                    generation += 1
                    active = null
                }
            }
        }.getOrElse {
            rejected(it)
            return false
        }
        superseded?.let { pending.remove(it.id) }
        if (superseded != null) publishPending()
        return true
    }

    fun dismiss(invitation: Invitation): Boolean {
        var matched = true
        val superseded = runCatching {
            synchronized(lock) {
                val previous = claim
                if (previous != null && previous.invitation != invitation) {
                    matched = false
                    return@synchronized null
                }
                store.clearClaim()
                claim = null
                active?.takeIf { it.claim != null && it.claim == previous }?.also {
                    generation += 1
                    active = null
                }
            }
        }.getOrElse {
            rejected(it)
            return false
        }
        if (!matched) return false
        superseded?.let { pending.remove(it.id) }
        if (superseded != null) publishPending()
        return true
    }

    fun pair(invitation: Invitation): Boolean {
        val pendingClaim = runCatching {
            synchronized(lock) {
                claim?.takeIf { it.invitation == invitation }
                    ?: Claim(invitation, nonce()).also {
                        store.saveClaim(it)
                        claim = it
                    }
            }
        }.getOrElse {
            rejected(it)
            return false
        }
        return start(pendingClaim) { client.claim(invitation, pendingClaim.nonce) }
    }

    private fun start(claim: Claim? = null, resolve: suspend () -> Config): Boolean {
        val revocations = loadRevocations() ?: return false
        if (revocations.isNotEmpty()) {
            rejected(IllegalStateException("Vibe Pocket is still revoking the previous device credential."))
            retryRevocation()
            return false
        }
        val attempt = synchronized(lock) {
            if (active != null) return false
            val next = ++generation
            Attempt(next, "$InFlightPrefix$next", claim).also { active = it }
        }
        if (!pending.add(attempt.id)) {
            synchronized(lock) { if (active === attempt) active = null }
            return false
        }
        publishPending()
        scope.launch(dispatcher) {
            var issued: Config? = null
            val verified = runCatching {
                val config = resolve().also { if (attempt.claim != null) issued = it }
                config to client.snapshot(config)
            }
            if (!isCurrent(attempt)) {
                pending.remove(attempt.id)
                issued?.let(::retireIssued)
                publishPending()
                return@launch
            }
            verified.onSuccess { (config, snapshot) -> commit(attempt, config, snapshot) }
                .onFailure { error ->
                    if (!isCurrent(attempt)) issued?.let(::retireIssued)
                    fail(attempt, error)
                }
        }
        return true
    }

    private fun commit(attempt: Attempt, config: Config, snapshot: Snapshot) {
        runCatching {
            synchronized(lock) {
                check(active === attempt && generation == attempt.generation) {
                    "Connection change was superseded."
                }
                if (attempt.claim == null) store.save(config) else store.commit(config)
                pending.remove(attempt.id)
                connected(config, snapshot.copy(transportFresh = true))
                if (attempt.claim != null) claim = null
                active = null
            }
        }.onFailure { fail(attempt, it) }
    }

    private fun fail(attempt: Attempt, error: Throwable) {
        pending.remove(attempt.id)
        val currentAttempt = synchronized(lock) {
            if (active !== attempt || generation != attempt.generation) {
                false
            } else {
                active = null
                true
            }
        }
        if (currentAttempt) {
            publishPending()
            rejected(error)
        }
    }

    private fun isCurrent(attempt: Attempt): Boolean = synchronized(lock) {
        active === attempt && generation == attempt.generation
    }

    fun disconnect(): Boolean {
        var previous: Config? = null
        val superseded = runCatching {
            synchronized(lock) {
                val existing = current().also { previous = it }
                if (existing == null || !existing.isDeviceCredential) {
                    store.invalidate()
                } else {
                    store.forget(existing)
                }
                generation += 1
                claim = null
                active.also {
                    active = null
                    disconnected()
                }
            }
        }.getOrElse { error ->
            rejected(error)
            previous?.let(recover)
            return false
        }
        superseded?.let { pending.remove(it.id) }
        retryRevocation()
        return true
    }

    fun invalidate(config: Config): Boolean {
        val superseded = runCatching {
            synchronized(lock) {
                val existing = current() ?: return false
                if (!existing.matches(config)) return false
                store.invalidate()
                generation += 1
                claim = null
                active.also {
                    active = null
                    disconnected()
                }
            }
        }.getOrElse { error ->
            rejected(error)
            return false
        }
        superseded?.let { pending.remove(it.id) }
        return true
    }

    private fun retireIssued(config: Config) {
        runCatching { store.enqueueRevocation(config) }
            .onFailure {
                rejected(it)
                return
            }
        retryRevocation()
    }

    private fun retryRevocation() {
        val launch = synchronized(lock) {
            if (revoking || loadRevocations().isNullOrEmpty()) false else true.also { revoking = it }
        }
        if (!launch) return
        scope.launch(dispatcher) {
            var retryDelay = retry.initialDelayMillis
            try {
                while (isActive) {
                    val voiceStop = store.loadVoiceStop()
                    val queued = loadRevocations() ?: return@launch
                    if (queued.isEmpty()) return@launch
                    val config = queued.firstOrNull { candidate ->
                        voiceStop == null || !voiceStop.config.matches(candidate)
                    }
                    if (config == null) {
                        delay(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(retry.maxDelayMillis)
                        continue
                    }
                    val result = runCatching {
                        withTimeout(retry.timeoutMillis) { client.revoke(config) }
                    }
                    val revoked = result.isSuccess ||
                        (result.exceptionOrNull() as? Failure)?.statusCode == 401
                    if (revoked) {
                        runCatching { store.removeRevocation(config) }
                            .onSuccess {
                                retryDelay = retry.initialDelayMillis
                                continue
                            }
                    }
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(retry.maxDelayMillis)
                }
            } finally {
                val relaunch = synchronized(lock) {
                    revoking = false
                    !loadRevocations().isNullOrEmpty()
                }
                if (relaunch) retryRevocation()
            }
        }
    }

    private fun loadRevocations(): List<Config>? = when (val loaded = store.loadRevocationsRecord()) {
        LoadOutcome.Absent -> emptyList()
        is LoadOutcome.Loaded -> loaded.value
        is LoadOutcome.RecoverableError -> {
            rejected(loaded.asException())
            null
        }
    }

    private companion object {
        const val InFlightPrefix = "connection:"
    }
}

private fun Config.matches(other: Config): Boolean =
    normalizedUrl == other.normalizedUrl && credential == other.credential
