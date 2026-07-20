package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.control.Snapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class Refresh(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val client: Client,
    private val state: MutableStateFlow<State>,
    private val reconcile: (Snapshot, Snapshot?) -> Snapshot,
    private val reconciled: (Snapshot, Long) -> Unit,
    private val persistentError: () -> String? = { null },
) {
    private class Lease(
        val config: Config,
        val generation: Long,
    )

    private data class Request(
        val lease: Lease,
        val version: Long,
    )

    private class Flight(
        val first: Request,
        var job: Job? = null,
    )

    private val lock = Any()
    private var lease: Lease? = null
    private var version = 0L
    private var flight: Flight? = null
    private var queued: Request? = null

    fun activate(config: Config, generation: Long) {
        invalidate(Lease(config, generation))
    }

    fun deactivate() {
        invalidate(null)
    }

    fun request(
        generation: Long? = null,
        stale: Boolean = false,
        onPrepared: (Long) -> Unit = {},
    ): Long? {
        var launch: Flight? = null
        val request = synchronized(lock) {
            val current = lease ?: return null
            if (generation != null && current.generation != generation) return null
            val request = Request(current, ++version)
            if (flight == null) {
                launch = Flight(request).also { flight = it }
            } else {
                queued = request
            }
            request
        }
        onPrepared(request.version)
        state.update { current ->
            if (current.config != request.lease.config || !isCurrent(request.lease)) {
                current
            } else {
                current.copy(
                    snapshot = if (stale) current.snapshot?.copy(transportFresh = false) else current.snapshot,
                    isRefreshing = current.snapshot == null,
                )
            }
        }
        launch?.let(::start)
        return request.version
    }

    private fun start(owner: Flight) {
        lateinit var candidate: Job
        candidate = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            var request: Request? = owner.first
            while (request != null) {
                val current = request
                val result = runCatching { client.snapshot(current.lease.config) }
                publish(owner, current, result)
                request = next(owner)
            }
        }
        val installed = synchronized(lock) {
            if (flight === owner && lease === owner.first.lease && owner.job == null) {
                owner.job = candidate
                true
            } else {
                false
            }
        }
        if (!installed) {
            candidate.cancel()
            return
        }
        candidate.invokeOnCompletion { error ->
            if (error != null && error !is kotlinx.coroutines.CancellationException) {
                fail(owner, error)
            }
            finish(owner)
        }
        candidate.start()
    }

    private fun publish(
        owner: Flight,
        request: Request,
        result: Result<Snapshot>,
    ) {
        val applied = synchronized(lock) {
            if (flight !== owner || lease !== request.lease) return
            if (result.isSuccess && queued != null) {
                false
            } else result.fold(
                onSuccess = { remote ->
                    var updated = false
                    state.update { current ->
                        updated = false
                        if (current.config != request.lease.config) {
                            current
                        } else {
                            updated = true
                            current.copy(
                                snapshot = reconcile(remote, current.snapshot),
                                isRefreshing = false,
                                error = persistentError(),
                            )
                        }
                    }
                    updated
                },
                onFailure = { error ->
                    state.update { current ->
                        if (current.config != request.lease.config) {
                            current
                        } else {
                            current.copy(
                                snapshot = current.snapshot?.copy(transportFresh = false),
                                isRefreshing = current.snapshot == null && queued != null,
                                error = error.message ?: persistentError(),
                            )
                        }
                    }
                    false
                },
            )
        }
        if (applied) reconciled(result.getOrThrow(), request.version)
    }

    private fun next(owner: Flight): Request? = synchronized(lock) {
        if (flight !== owner) return@synchronized null
        queued.also {
            queued = null
            if (it == null) flight = null
        }
    }

    private fun fail(owner: Flight, error: Throwable) {
        synchronized(lock) {
            if (flight !== owner || lease !== owner.first.lease) return
            state.update { current ->
                if (current.config != owner.first.lease.config) current else current.copy(
                    snapshot = current.snapshot?.copy(transportFresh = false),
                    isRefreshing = false,
                    error = error.message ?: persistentError(),
                )
            }
        }
    }

    private fun finish(owner: Flight) {
        var successor: Flight? = null
        synchronized(lock) {
            if (flight !== owner) return
            val request = queued
            queued = null
            if (request == null || lease !== request.lease) {
                flight = null
            } else {
                successor = Flight(request).also { flight = it }
            }
        }
        successor?.let(::start)
    }

    private fun invalidate(next: Lease?) {
        val previous = synchronized(lock) {
            lease = next
            version += 1
            queued = null
            flight?.job.also { flight = null }
        }
        previous?.cancel()
        state.update { it.copy(isRefreshing = false) }
    }

    private fun isCurrent(candidate: Lease): Boolean = synchronized(lock) { lease === candidate }
}
