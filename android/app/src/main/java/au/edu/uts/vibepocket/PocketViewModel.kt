package au.edu.uts.vibepocket

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PocketViewModel(private val store: SecureConfigStore) : ViewModel() {
    private val client = PocketBridgeClient()
    private val _state = MutableStateFlow(PocketUiState())
    val state: StateFlow<PocketUiState> = _state.asStateFlow()
    private var events: PocketEventStream? = null

    init {
        store.load()?.let { config ->
            _state.update { it.copy(config = config) }
            startEvents(config)
            refresh()
        }
    }

    fun connect(baseUrl: String, token: String) {
        val config = runCatching { ConnectionConfig(baseUrl.trim(), token.trim()) }
            .getOrElse {
                _state.update { state -> state.copy(error = it.message) }
                return
            }
        store.save(config)
        events?.stop()
        _state.update { it.copy(config = config, error = null, snapshot = null) }
        startEvents(config)
        refresh()
    }

    fun disconnect() {
        events?.stop()
        events = null
        store.clear()
        _state.value = PocketUiState()
    }

    fun refresh() {
        val config = _state.value.config ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isRefreshing = true, error = null) }
            runCatching { client.snapshot(config) }
                .onSuccess { snapshot -> _state.update { it.copy(snapshot = snapshot, isRefreshing = false) } }
                .onFailure { error -> _state.update { it.copy(isRefreshing = false, error = error.message) } }
        }
    }

    fun attach() = submit(PocketCommand.Attach)

    fun voice() = submit(PocketCommand.Voice)

    fun stop() = submit(PocketCommand.Stop)

    fun newTask() = submit(PocketCommand.NewTask)

    fun approve() = submit(PocketCommand.Approve)

    fun reject() = submit(PocketCommand.Reject)

    private fun submit(command: PocketCommand) {
        val config = _state.value.config ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(error = null) }
            runCatching { client.command(config, command) }
                .onSuccess { refresh() }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    private fun startEvents(config: ConnectionConfig) {
        events = PocketEventStream(
            config = config,
            onSnapshotChanged = ::refresh,
            onDisconnected = { message -> _state.update { it.copy(error = message) } },
        ).also(PocketEventStream::start)
    }

    override fun onCleared() {
        events?.stop()
    }
}

class PocketViewModelFactory(private val store: SecureConfigStore) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = PocketViewModel(store) as T
}
