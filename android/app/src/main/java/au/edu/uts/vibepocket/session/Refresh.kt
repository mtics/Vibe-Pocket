package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.sameSurface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class Refresh(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val client: Client,
    private val state: MutableStateFlow<State>,
    private val reconcile: (Snapshot, Snapshot?) -> Snapshot,
    private val reconciled: () -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val requested = AtomicBoolean(false)
    private val version = AtomicLong(0)

    fun request() {
        version.incrementAndGet()
        requested.set(true)
        if (!running.compareAndSet(false, true)) return
        scope.launch(dispatcher) {
            try {
                do {
                    requested.set(false)
                    val requestVersion = version.get()
                    val config = state.value.config ?: break
                    if (state.value.snapshot == null) state.update { it.copy(isRefreshing = true) }
                    runCatching { client.snapshot(config) }
                        .onSuccess { remote ->
                            if (state.value.config == config && version.get() == requestVersion) {
                                state.update { current ->
                                    val visible = current.snapshot
                                    val resolved = reconcile(remote.copy(transportFresh = true), visible)
                                    val next = if (visible != null && visible.sameSurface(resolved)) {
                                        visible
                                    } else {
                                        resolved
                                    }
                                    current.copy(snapshot = next, isRefreshing = false, error = null)
                                }
                                reconciled()
                            }
                        }
                        .onFailure { error ->
                            if (state.value.config == config && version.get() == requestVersion) {
                                state.update {
                                    it.copy(
                                        snapshot = it.snapshot?.copy(transportFresh = false),
                                        isRefreshing = false,
                                        error = error.message,
                                    )
                                }
                            }
                        }
                } while (requested.get())
            } finally {
                running.set(false)
                if (requested.get()) request()
            }
        }
    }
}
