package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.Store
import au.edu.uts.vibepocket.control.Snapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

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
) {
    private val generation = AtomicLong(0)
    private val lock = Any()

    fun connect(baseUrl: String, token: String): Boolean {
        val config = runCatching { Config(baseUrl.trim(), token.trim()) }
            .getOrElse {
                rejected(it)
                return false
            }
        val previous = current()
        if (previous?.normalizedUrl == config.normalizedUrl && previous.token == config.token) return false
        val attempt = synchronized(lock) {
            if (pending.any { it.startsWith(InFlightPrefix) }) return false
            generation.incrementAndGet()
        }
        val id = "$InFlightPrefix$attempt"
        if (!pending.add(id)) return false
        publishPending()
        scope.launch(dispatcher) {
            val verified = runCatching { client.snapshot(config) }
            if (generation.get() != attempt) {
                pending.remove(id)
                return@launch
            }
            verified.onSuccess { snapshot ->
                runCatching {
                    synchronized(lock) {
                        check(generation.get() == attempt) { "Connection change was superseded." }
                        store.save(config)
                        connected(config, snapshot)
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
                disconnected()
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
