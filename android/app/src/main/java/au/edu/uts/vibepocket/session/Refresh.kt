package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.sameSurface
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
    private val reconciled: () -> Unit,
    private val persistentError: () -> String? = { null },
) {
    private data class Lease(
        val config: Config,
        val generation: Long,
    )

    private data class Request(
        val lease: Lease,
        val version: Long,
    )

    private val lock = Any()
    private var lease: Lease? = null
    private var version = 0L
    private var job: Job? = null

    fun activate(config: Config, generation: Long) {
        invalidate(Lease(config, generation))
    }

    fun deactivate() {
        invalidate(null)
    }

    fun request(generation: Long? = null, stale: Boolean = false) {
        val prepared = synchronized(lock) {
            val current = lease ?: return
            if (generation != null && current.generation != generation) return
            val request = Request(current, ++version)
            val previous = job
            job = null
            request to previous
        }
        prepared.second?.cancel()
        state.update { current ->
            if (current.config != prepared.first.lease.config) {
                current
            } else {
                current.copy(
                    snapshot = if (stale) current.snapshot?.copy(transportFresh = false) else current.snapshot,
                    isRefreshing = current.snapshot == null,
                )
            }
        }

        lateinit var candidate: Job
        candidate = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            val result = runCatching { client.snapshot(prepared.first.lease.config) }
            if (!isCurrent(prepared.first)) return@launch
            result.onSuccess { remote ->
                state.update { current ->
                    if (current.config != prepared.first.lease.config || !isCurrent(prepared.first)) {
                        current
                    } else {
                        val visible = current.snapshot
                        val resolved = reconcile(remote.copy(transportFresh = true), visible)
                        val next = if (visible != null && visible.sameSurface(resolved)) visible else resolved
                        current.copy(
                            snapshot = next,
                            isRefreshing = false,
                            error = persistentError(),
                        )
                    }
                }
                if (isCurrent(prepared.first)) reconciled()
            }.onFailure { error ->
                state.update { current ->
                    if (current.config != prepared.first.lease.config || !isCurrent(prepared.first)) {
                        current
                    } else {
                        current.copy(
                            snapshot = current.snapshot?.copy(transportFresh = false),
                            isRefreshing = false,
                            error = error.message ?: persistentError(),
                        )
                    }
                }
            }
        }
        val installed = synchronized(lock) {
            if (isCurrentLocked(prepared.first) && job == null) {
                job = candidate
                true
            } else {
                false
            }
        }
        if (!installed) {
            candidate.cancel()
            return
        }
        candidate.invokeOnCompletion {
            synchronized(lock) {
                if (job === candidate) job = null
            }
        }
        candidate.start()
    }

    private fun invalidate(next: Lease?) {
        val previous = synchronized(lock) {
            lease = next
            version += 1
            job.also { job = null }
        }
        previous?.cancel()
        state.update { it.copy(isRefreshing = false) }
    }

    private fun isCurrent(request: Request): Boolean = synchronized(lock) {
        isCurrentLocked(request)
    }

    private fun isCurrentLocked(request: Request): Boolean =
        lease == request.lease && version == request.version
}
