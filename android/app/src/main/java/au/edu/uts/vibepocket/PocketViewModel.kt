package au.edu.uts.vibepocket

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class PocketViewModel(
    private val store: ConfigStore,
    private val client: PocketClient = PocketBridgeClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class VoiceOwner(
        val inputId: String,
        val config: ConnectionConfig,
    )

    private data class VoiceCommand(
        val command: PocketCommand,
        val config: ConnectionConfig,
    )

    private val _state = MutableStateFlow(PocketUiState())
    val state: StateFlow<PocketUiState> = _state.asStateFlow()
    private val _feedback = MutableSharedFlow<PocketFeedback>(extraBufferCapacity = 4)
    val feedback: SharedFlow<PocketFeedback> = _feedback.asSharedFlow()

    private val commandGate = AtomicBoolean(false)
    private val voiceStateLock = Any()
    private val pendingVoiceCommands = ArrayDeque<VoiceCommand>()
    private val voiceScopeJob = SupervisorJob()
    private val voiceScope = CoroutineScope(voiceScopeJob + ioDispatcher)
    private var voiceDrainRunning = false
    private var voiceOwner: VoiceOwner? = null
    private var pendingDictationConfig: ConnectionConfig? = null
    private var closeVoiceScopeWhenDrained = false
    private val refreshRunning = AtomicBoolean(false)
    private val refreshRequested = AtomicBoolean(false)
    @Volatile private var foreground = false
    @Volatile private var lastEventId: String? = null
    private var events: PocketEventStream? = null

    init {
        store.load()?.let { config ->
            _state.update { it.copy(config = config) }
            refresh()
        }
    }

    fun connect(baseUrl: String, token: String) {
        val config = runCatching { ConnectionConfig(baseUrl.trim(), token.trim()) }
            .getOrElse {
                _state.update { state -> state.copy(error = it.message) }
                _feedback.tryEmit(PocketFeedback.Error)
                return
            }
        store.save(config)
        stopEvents()
        lastEventId = null
        _state.value = PocketUiState(config = config)
        if (foreground) startEvents(config)
        refresh()
    }

    fun disconnect() {
        stopOwnedVoice()
        stopEvents()
        lastEventId = null
        store.clear()
        _state.value = PocketUiState()
    }

    fun setForeground(isForeground: Boolean) {
        if (!isForeground) stopOwnedVoice()
        if (foreground == isForeground) return
        foreground = isForeground
        if (isForeground) {
            _state.value.config?.let(::startEvents)
            refresh()
        } else {
            stopEvents()
        }
    }

    fun refresh() {
        refreshRequested.set(true)
        if (!refreshRunning.compareAndSet(false, true)) return
        viewModelScope.launch(ioDispatcher) {
            try {
                do {
                    refreshRequested.set(false)
                    val config = _state.value.config ?: break
                    _state.update { it.copy(isRefreshing = true) }
                    runCatching { client.snapshot(config) }
                        .onSuccess { snapshot ->
                            if (_state.value.config == config) {
                                _state.update { it.copy(snapshot = snapshot, isRefreshing = false, error = null) }
                            }
                        }
                        .onFailure { error ->
                            if (_state.value.config == config) {
                                _state.update { it.copy(isRefreshing = false, error = error.message) }
                            }
                        }
                } while (refreshRequested.get())
            } finally {
                refreshRunning.set(false)
                if (refreshRequested.get()) refresh()
            }
        }
    }

    fun activateInput(
        inputId: String,
        gesture: ControllerGesture = ControllerGesture.TAP,
    ): Boolean {
        val snapshot = _state.value.snapshot ?: return false
        if (!snapshot.inputEnabled(inputId, gesture)) return false
        return submit(snapshot.commandForInput(inputId, gesture), "input:$inputId:${gesture.wireValue}")
    }

    fun startVoice(inputId: String): Boolean {
        val current = _state.value
        val config = current.config ?: return false
        val snapshot = current.snapshot ?: return false
        if (!snapshot.voiceTapEnabled(inputId)) return false
        val shouldLaunch = synchronized(voiceStateLock) {
            if (voiceOwner != null) return@synchronized null
            voiceOwner = VoiceOwner(inputId, config)
            pendingDictationConfig = null
            pendingVoiceCommands.addLast(VoiceCommand(PocketCommand.VoiceStart, config))
            (!voiceDrainRunning).also { launch ->
                if (launch) voiceDrainRunning = true
            }
        } ?: return false
        if (shouldLaunch) launchVoiceDrain()
        return true
    }

    fun stopVoice(inputId: String): Boolean {
        return stopOwnedVoice(inputId, acceptDictation = true)
    }

    fun submitDictation(text: String): Boolean {
        val normalized = text.trim()
        if (
            normalized.isEmpty() ||
            normalized.length > 12_000 ||
            normalized.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        ) return false
        val shouldLaunch = synchronized(voiceStateLock) {
            val config = pendingDictationConfig ?: return@synchronized null
            pendingDictationConfig = null
            if (_state.value.config != config) return@synchronized null
            pendingVoiceCommands.addLast(VoiceCommand(PocketCommand.DictationResult(normalized), config))
            (!voiceDrainRunning).also { launch ->
                if (launch) voiceDrainRunning = true
            }
        } ?: return false
        if (shouldLaunch) launchVoiceDrain()
        return true
    }

    fun reportDictationError(message: String) {
        synchronized(voiceStateLock) { pendingDictationConfig = null }
        reportLocalError(message)
    }

    fun reportLocalError(message: String) {
        _state.update { it.copy(error = message) }
        _feedback.tryEmit(PocketFeedback.Error)
    }

    private fun stopOwnedVoice(inputId: String? = null, acceptDictation: Boolean = false): Boolean {
        val shouldLaunch = synchronized(voiceStateLock) {
            val owner = voiceOwner?.takeIf { inputId == null || it.inputId == inputId }
                ?: return@synchronized null
            voiceOwner = null
            pendingDictationConfig = owner.config.takeIf { acceptDictation }
            pendingVoiceCommands.addLast(VoiceCommand(PocketCommand.VoiceStop, owner.config))
            (!voiceDrainRunning).also { launch ->
                if (launch) voiceDrainRunning = true
            }
        } ?: return false
        if (shouldLaunch) launchVoiceDrain()
        return true
    }

    fun focusAgent(agentId: String): Boolean {
        val snapshot = _state.value.snapshot ?: return false
        if (!snapshot.agentFocusEnabled(agentId)) return false
        return submit(PocketCommand.FocusAgent(agentId), "agent:$agentId")
    }

    fun selectLayer(layerId: String): Boolean {
        val controller = _state.value.snapshot?.controller ?: return false
        if (controller.profile?.layers?.none { it.id == layerId } != false) return false
        if (controller.activeLayerId == layerId) return false
        return submit(PocketCommand.SelectLayer(layerId), "layer:$layerId")
    }

    fun updateBinding(
        layerId: String,
        inputId: String,
        gesture: ControllerGesture,
        actionId: String,
    ): Boolean {
        val controller = _state.value.snapshot?.controller ?: return false
        val profile = controller.profile ?: return false
        if (profile.layers.none { it.id == layerId } || profile.inputs.none { it.id == inputId }) return false
        val action = controller.actionCatalog.firstOrNull { it.id == actionId }?.action ?: return false
        return submit(
            PocketCommand.UpdateBinding(layerId, inputId, gesture, action),
            "mapping:$inputId:${gesture.wireValue}",
        )
    }

    fun clearBinding(layerId: String, inputId: String, gesture: ControllerGesture): Boolean {
        val profile = _state.value.snapshot?.controller?.profile ?: return false
        val layer = profile.layers.firstOrNull { it.id == layerId } ?: return false
        if (layer.bindings[inputId]?.actions?.containsKey(gesture) != true) return false
        return submit(
            PocketCommand.ClearBinding(layerId, inputId, gesture),
            "mapping:$inputId:${gesture.wireValue}",
        )
    }

    fun renameLayer(layerId: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 40 || trimmed.any { it.isISOControl() }) return false
        val layer = _state.value.snapshot?.controller?.profile?.layers?.firstOrNull { it.id == layerId } ?: return false
        if (layer.name == trimmed) return false
        return submit(PocketCommand.RenameLayer(layerId, trimmed), "rename:$layerId")
    }

    fun updateLayerColor(layerId: String, color: String): Boolean {
        if (!color.matches(Regex("#[0-9a-fA-F]{6}"))) return false
        val layer = _state.value.snapshot?.controller?.profile?.layers?.firstOrNull { it.id == layerId } ?: return false
        if (layer.color.equals(color, ignoreCase = true)) return false
        return submit(PocketCommand.UpdateLayerColor(layerId, color.uppercase()), "color:$layerId")
    }

    fun updateWorkflowPrompt(workflowId: String, prompt: String): Boolean {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || trimmed.length > 4_000 || trimmed.any { it.isISOControl() && it != '\n' && it != '\t' }) return false
        val workflow = _state.value.snapshot?.controller?.profile?.workflows?.firstOrNull { it.id == workflowId } ?: return false
        if (workflow.prompt == trimmed) return false
        return submit(PocketCommand.UpdateWorkflowPrompt(workflowId, trimmed), "workflow:$workflowId")
    }

    fun resetProfile(): Boolean {
        if (_state.value.snapshot?.controller?.profile == null) return false
        return submit(PocketCommand.ResetProfile, "reset-profile")
    }

    private fun submit(command: PocketCommand, inFlightId: String): Boolean {
        val config = _state.value.config ?: return false
        if (!commandGate.compareAndSet(false, true)) return false
        _state.update { it.copy(inFlightId = inFlightId, error = null) }
        viewModelScope.launch(ioDispatcher) {
            runCatching { client.command(config, command) }
                .onSuccess {
                    if (_state.value.config == config) {
                        _feedback.tryEmit(PocketFeedback.Success)
                        refresh()
                    }
                }
                .onFailure { error ->
                    if (_state.value.config == config) {
                        _state.update { it.copy(error = error.message) }
                        _feedback.tryEmit(PocketFeedback.Error)
                    }
                }
            commandGate.set(false)
            if (_state.value.config == config) {
                _state.update { current ->
                    current.copy(inFlightId = current.inFlightId.takeUnless { it == inFlightId })
                }
            }
        }
        return true
    }

    private fun launchVoiceDrain() {
        voiceScope.launch {
            while (true) {
                var closeScope = false
                val transition = synchronized(voiceStateLock) {
                    if (pendingVoiceCommands.isEmpty()) {
                        voiceDrainRunning = false
                        closeScope = closeVoiceScopeWhenDrained
                        null
                    } else {
                        pendingVoiceCommands.removeFirst()
                    }
                }
                if (transition == null) {
                    if (closeScope) voiceScopeJob.cancel()
                    return@launch
                }
                runCatching { client.command(transition.config, transition.command) }
                    .onSuccess {
                        if (_state.value.config == transition.config) {
                            _feedback.tryEmit(PocketFeedback.Success)
                            refresh()
                        }
                    }
                    .onFailure { error ->
                        if (_state.value.config == transition.config) {
                            _state.update { it.copy(error = error.message) }
                            _feedback.tryEmit(PocketFeedback.Error)
                        }
                    }
            }
        }
    }

    private fun startEvents(config: ConnectionConfig) {
        if (events != null || !foreground || _state.value.config != config) return
        events = PocketEventStream(
            config = config,
            lastEventId = lastEventId,
            onSnapshotChanged = ::refresh,
            onEventId = { lastEventId = it },
            onDisconnected = { message ->
                if (foreground && _state.value.config == config) {
                    _state.update { it.copy(error = message) }
                }
            },
        ).also(PocketEventStream::start)
    }

    private fun stopEvents() {
        events?.stop()
        events = null
    }

    override fun onCleared() {
        stopOwnedVoice()
        stopEvents()
        val closeNow = synchronized(voiceStateLock) {
            closeVoiceScopeWhenDrained = true
            !voiceDrainRunning && pendingVoiceCommands.isEmpty()
        }
        if (closeNow) voiceScopeJob.cancel()
        super.onCleared()
    }
}

class PocketViewModelFactory(private val store: SecureConfigStore) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = PocketViewModel(store) as T
}
