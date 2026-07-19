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

internal class Refresh(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val client: Client,
    private val state: MutableStateFlow<State>,
    private val reconcile: (Snapshot, Snapshot?) -> Snapshot,
) {
    private val running = AtomicBoolean(false)
    private val requested = AtomicBoolean(false)

    fun request() {
        requested.set(true)
        if (!running.compareAndSet(false, true)) return
        scope.launch(dispatcher) {
            try {
                do {
                    requested.set(false)
                    val config = state.value.config ?: break
                    if (state.value.snapshot == null) state.update { it.copy(isRefreshing = true) }
                    runCatching { client.snapshot(config) }
                        .onSuccess { remote ->
                            if (state.value.config == config) {
                                state.update { current ->
                                    val visible = current.snapshot
                                    val reconciled = reconcile(remote, visible)
                                    val next = if (visible != null && visible.sameSurface(reconciled)) {
                                        visible
                                    } else {
                                        reconciled
                                    }
                                    current.copy(snapshot = next, isRefreshing = false, error = null)
                                }
                            }
                        }
                        .onFailure { error ->
                            if (state.value.config == config) {
                                state.update { it.copy(isRefreshing = false, error = error.message) }
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
