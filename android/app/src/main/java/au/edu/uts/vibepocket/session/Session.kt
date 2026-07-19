package au.edu.uts.vibepocket.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.bridge.Events
import au.edu.uts.vibepocket.bridge.Http
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.Invitation
import au.edu.uts.vibepocket.connection.Store
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Gesture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class Session(
    private val store: Store,
    private val client: Client = Http(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _feedback = MutableSharedFlow<Feedback>(extraBufferCapacity = 4)
    val feedback: SharedFlow<Feedback> = _feedback.asSharedFlow()

    private val pending = Pending()
    @Volatile private var foreground = false
    @Volatile private var lastEventId: String? = null
    @Volatile private var eventError: String? = null
    private var events: Events? = null

    private val prediction = Prediction(nowMillis)
    private val refresh = Refresh(
        scope = viewModelScope,
        dispatcher = dispatcher,
        client = client,
        state = _state,
        reconcile = prediction::reconcile,
    )
    private val voice = Voice(
        dispatcher = dispatcher,
        client = client,
        accepted = ::commandAccepted,
        rejected = ::commandRejected,
    )
    private val delivery = Delivery(
        scope = viewModelScope,
        dispatcher = dispatcher,
        client = client,
        pending = pending,
        config = { _state.value.config },
        publishPending = ::publishPending,
        accepted = ::commandAccepted,
        rejected = ::commandRejected,
    )
    private val commands = Commands(
        snapshot = { _state.value.snapshot },
        deliver = delivery::send,
    )
    private val connection = Connection(
        scope = viewModelScope,
        dispatcher = dispatcher,
        client = client,
        store = store,
        pending = pending,
        current = { _state.value.config },
        publishPending = ::publishPending,
        connected = ::connectionAccepted,
        disconnected = ::connectionCleared,
        rejected = ::connectionRejected,
        recover = { if (foreground) startEvents(it) },
    )

    init {
        store.load()?.let { config ->
            _state.update { it.copy(config = config) }
            refresh.request()
        }
    }

    fun connect(baseUrl: String, credential: String): Boolean = connection.connect(baseUrl, credential)

    fun offer(value: String): Boolean {
        val invitation = runCatching { Invitation.parse(value) }
            .getOrElse {
                connectionRejected(it)
                return false
            }
        _state.update { it.copy(invitation = invitation, error = null) }
        return true
    }

    fun pair(): Boolean {
        val invitation = _state.value.invitation ?: return false
        return connection.pair(invitation)
    }

    fun dismissPairing() {
        _state.update { it.copy(invitation = null) }
    }

    fun disconnect() = connection.disconnect()

    fun setForeground(value: Boolean) {
        if (!value) voice.stop()
        if (foreground == value) return
        foreground = value
        if (value) {
            _state.value.config?.let(::startEvents)
            refresh.request()
        } else {
            stopEvents()
        }
    }

    fun refresh() = refresh.request()

    fun activateInput(
        inputId: String,
        gesture: Gesture.Kind = Gesture.Kind.TAP,
    ): Boolean = commands.activate(inputId, gesture)

    fun openModel(): Boolean = commands.openModel()

    fun startVoice(inputId: String): Boolean {
        val current = _state.value
        val config = current.config ?: return false
        val snapshot = current.snapshot ?: return false
        if (!snapshot.voiceTapEnabled(inputId)) return false
        return voice.start(inputId, config)
    }

    fun stopVoice(inputId: String): Boolean = voice.stop(inputId)

    fun reportLocalError(message: String) {
        _state.update { it.copy(error = message) }
        _feedback.tryEmit(Feedback.Error)
    }

    fun applyLocalAction(action: Action) {
        _state.update { prediction.apply(it, action) }
    }

    fun focusAgent(agentId: String): Boolean = commands.focusAgent(agentId)

    fun selectModel(modelId: String): Boolean = commands.selectModel(modelId)

    fun selectLayer(layerId: String): Boolean = commands.selectLayer(layerId)

    fun updateBinding(
        layerId: String,
        inputId: String,
        gesture: Gesture.Kind,
        actionId: String,
    ): Boolean = commands.updateBinding(layerId, inputId, gesture, actionId)

    fun clearBinding(layerId: String, inputId: String, gesture: Gesture.Kind): Boolean =
        commands.clearBinding(layerId, inputId, gesture)

    fun renameLayer(layerId: String, name: String): Boolean = commands.renameLayer(layerId, name)

    fun updateLayerColor(layerId: String, color: String): Boolean = commands.updateLayerColor(layerId, color)

    fun updateWorkflowPrompt(workflowId: String, prompt: String): Boolean =
        commands.updateWorkflow(workflowId, prompt)

    fun resetProfile(): Boolean = commands.resetProfile()

    private fun connectionAccepted(config: Config, snapshot: Snapshot) {
        voice.stop()
        stopEvents()
        prediction.clear()
        lastEventId = null
        eventError = null
        pending.clear()
        _state.value = State(config = config, snapshot = snapshot, invitation = null)
        if (foreground) startEvents(config)
        _feedback.tryEmit(Feedback.Success)
    }

    private fun connectionCleared() {
        voice.stop()
        stopEvents()
        prediction.clear()
        lastEventId = null
        eventError = null
        pending.clear()
        _state.value = State()
    }

    private fun connectionRejected(error: Throwable) {
        _state.update { it.copy(inFlightIds = pending.snapshot(), error = error.message) }
        _feedback.tryEmit(Feedback.Error)
    }

    private fun commandAccepted(config: Config) {
        if (_state.value.config != config) return
        _feedback.tryEmit(Feedback.Success)
        if (!foreground) refresh.request()
    }

    private fun commandRejected(config: Config, error: Throwable) {
        if (_state.value.config != config) return
        _state.update { it.copy(error = error.message) }
        _feedback.tryEmit(Feedback.Error)
    }

    private fun publishPending() {
        _state.update { it.copy(inFlightIds = pending.snapshot(), error = null) }
    }

    private fun startEvents(config: Config) {
        if (events != null || !foreground || _state.value.config != config) return
        events = Events(
            config = config,
            lastEventId = lastEventId,
            onConnected = {
                val recovered = eventError
                eventError = null
                if (recovered != null && foreground && _state.value.config == config) {
                    _state.update { current ->
                        if (current.error == recovered) current.copy(error = null) else current
                    }
                }
            },
            onSnapshotChanged = refresh::request,
            onEventId = { lastEventId = it },
            onDisconnected = { message ->
                if (foreground && _state.value.config == config) {
                    eventError = message
                    _state.update { it.copy(error = message) }
                }
            },
        ).also(Events::start)
    }

    private fun stopEvents() {
        events?.stop()
        events = null
    }

    override fun onCleared() {
        voice.close()
        stopEvents()
        super.onCleared()
    }

    companion object {
        fun create(store: Store): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = Session(store) as T
        }
    }
}
