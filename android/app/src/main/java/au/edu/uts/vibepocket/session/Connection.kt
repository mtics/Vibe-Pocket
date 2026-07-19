package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.Invitation
import au.edu.uts.vibepocket.connection.Store
import au.edu.uts.vibepocket.control.Snapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
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
) {
    private val generation = AtomicLong(0)
    private val lock = Any()
    private var retry: Pair<Invitation, String>? = null

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

    fun pair(invitation: Invitation): Boolean {
        val claimNonce = synchronized(lock) {
            retry?.takeIf { it.first == invitation }?.second
                ?: nonce().also { retry = invitation to it }
        }
        return start(pairing = true) { client.claim(invitation, claimNonce) }
    }

    private fun start(pairing: Boolean = false, resolve: suspend () -> Config): Boolean {
        val attempt = synchronized(lock) {
            if (pending.any { it.startsWith(InFlightPrefix) }) return false
            generation.incrementAndGet()
        }
        val id = "$InFlightPrefix$attempt"
        if (!pending.add(id)) return false
        publishPending()
        scope.launch(dispatcher) {
            val verified = runCatching {
                val config = resolve()
                config to client.snapshot(config)
            }
            if (generation.get() != attempt) {
                pending.remove(id)
                return@launch
            }
            verified.onSuccess { (config, snapshot) ->
                runCatching {
                    synchronized(lock) {
                        check(generation.get() == attempt) { "Connection change was superseded." }
                        store.save(config)
                        connected(config, snapshot)
                        if (pairing) retry = null
                    }
                }.onFailure { error ->
                    pending.remove(id)
                    if (generation.get() == attempt) {
                        publishPending()
                        rejected(error)
                    }
                }
            }.onFailure { error ->
                pending.remove(id)
                if (generation.get() == attempt) {
                    publishPending()
                    rejected(error)
                }
            }
        }
        return true
    }

    fun disconnect() {
        val previous = current()
        runCatching {
            synchronized(lock) {
                generation.incrementAndGet()
                store.clear()
                retry = null
                disconnected()
            }
            previous?.let { config ->
                scope.launch(dispatcher) { runCatching { client.revoke(config) } }
            }
        }.onFailure { error ->
            rejected(error)
            previous?.let(recover)
        }
    }

    private companion object {
        const val InFlightPrefix = "connection:"
    }
}
