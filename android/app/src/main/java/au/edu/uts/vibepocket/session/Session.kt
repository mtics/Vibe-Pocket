package au.edu.uts.vibepocket.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.bridge.EventCallbacks
import au.edu.uts.vibepocket.bridge.EventFactory
import au.edu.uts.vibepocket.bridge.EventStream
import au.edu.uts.vibepocket.bridge.Http
import au.edu.uts.vibepocket.bridge.NetworkEvents
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.Invitation
import au.edu.uts.vibepocket.connection.Store
import au.edu.uts.vibepocket.connection.loadLifecycle
import au.edu.uts.vibepocket.connection.valueOrNull
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Gesture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class Session internal constructor(
    private val store: Store,
    private val client: Client = Http(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val eventFactory: EventFactory = NetworkEvents,
    retry: Retry = Retry(),
) : ViewModel() {
    private val restored = store.loadLifecycle()
    private var lifecycleErrors = restored.errors
    private val _state = MutableStateFlow(State(error = lifecycleErrorMessage()))
    val state: StateFlow<State> = _state.asStateFlow()
    private val _feedback = MutableSharedFlow<Feedback>(extraBufferCapacity = 4)
    val feedback: SharedFlow<Feedback> = _feedback.asSharedFlow()

    private val pending = Pending()
    @Volatile private var foreground = false
    @Volatile private var lastEventId: String? = null
    @Volatile private var eventError: String? = null
    @Volatile private var activeEventGeneration: Long? = null
    private var events: EventStream? = null
    private var eventGeneration = 0L
    private var predictionExpiry: Job? = null

    private val prediction = Prediction(nowMillis)
    private val refresh = Refresh(
        scope = viewModelScope,
        dispatcher = dispatcher,
        client = client,
        state = _state,
        reconcile = prediction::reconcile,
        reconciled = ::predictionReconciled,
        persistentError = ::lifecycleErrorMessage,
    )
    private val voice = Voice(
        dispatcher = dispatcher,
        client = client,
        store = store,
        accepted = ::commandAccepted,
        rejected = ::commandRejected,
        retry = retry,
    )
    private val delivery = Delivery(
        scope = viewModelScope,
        dispatcher = dispatcher,
        client = client,
        pending = pending,
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
        nowMillis = nowMillis,
        retry = retry,
    )

    init {
        val config = restored.config.valueOrNull()
        if (config != null) {
            _state.update {
                it.copy(
                    config = config,
                    invitation = connection.pendingInvitation,
                    error = lifecycleErrorMessage(),
                )
            }
            delivery.bind(config)
            refresh.activate(config, eventGeneration)
            refresh.request()
        } else {
            connection.pendingInvitation?.let { invitation ->
                _state.update { it.copy(invitation = invitation, error = lifecycleErrorMessage()) }
            }
        }
        voice.restore()
        connection.restore()
    }

    fun connect(baseUrl: String, credential: String): Boolean = connection.connect(baseUrl, credential)

    fun offer(value: String): Boolean {
        val invitation = runCatching { Invitation.parse(value, nowMillis) }
            .getOrElse {
                connectionRejected(it)
                return false
            }
        if (!connection.offer(invitation)) return false
        _state.update { it.copy(invitation = invitation, error = lifecycleErrorMessage()) }
        return true
    }

    fun pair(): Boolean {
        val invitation = _state.value.invitation ?: return false
        return connection.pair(invitation)
    }

    fun dismissPairing(): Boolean {
        val invitation = _state.value.invitation ?: return false
        if (!connection.dismiss(invitation)) return false
        reloadLifecycleErrors()
        _state.update { it.copy(invitation = null, error = lifecycleErrorMessage()) }
        return true
    }

    fun disconnect() {
        voice.stop()
        val config = _state.value.config
        delivery.bind(null)
        if (!connection.disconnect()) delivery.bind(config)
    }

    fun setForeground(value: Boolean) {
        if (!value) voice.stop()
        if (!value) {
            foreground = false
            stopEvents(stale = true)
            return
        }
        if (foreground) return
        foreground = value
        _state.value.config?.let(::startEvents)
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

    fun stopVoice(inputId: String): Boolean = voice.stop(inputId, _state.value.config)

    fun reportLocalError(message: String) {
        _state.update { it.copy(error = message) }
        _feedback.tryEmit(Feedback.Error)
    }

    fun reportLocalDeliveryFailure(message: String) {
        predictionExpiry?.cancel()
        predictionExpiry = null
        _state.update { prediction.fail(it).copy(error = message) }
        _feedback.tryEmit(Feedback.Error)
    }

    fun applyLocalAction(action: Action) {
        _state.update { prediction.apply(it, action) }
        schedulePredictionExpiry()
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
        predictionExpiry?.cancel()
        predictionExpiry = null
        prediction.clear()
        lastEventId = null
        eventError = null
        pending.clear()
        reloadLifecycleErrors()
        _state.value = State(
            config = config,
            snapshot = snapshot,
            invitation = null,
            error = lifecycleErrorMessage(),
        )
        delivery.bind(config)
        if (foreground) {
            startEvents(config)
        } else {
            refresh.activate(config, ++eventGeneration)
        }
        _feedback.tryEmit(Feedback.Success)
    }

    private fun connectionCleared() {
        voice.stop()
        delivery.bind(null)
        stopEvents()
        predictionExpiry?.cancel()
        predictionExpiry = null
        prediction.clear()
        lastEventId = null
        eventError = null
        pending.clear()
        reloadLifecycleErrors()
        _state.value = State(
            invitation = connection.pendingInvitation,
            error = lifecycleErrorMessage(),
        )
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
        _state.update { it.copy(inFlightIds = pending.snapshot(), error = lifecycleErrorMessage()) }
    }

    private fun startEvents(config: Config) {
        if (events != null || !foreground || _state.value.config != config) return
        val generation = ++eventGeneration
        activeEventGeneration = generation
        refresh.activate(config, generation)
        markSnapshotStale(config, generation)
        val stream = eventFactory.create(
            config,
            lastEventId,
            EventCallbacks(
                connected = {
                    if (isCurrentEvent(config, generation)) {
                        val recovered = eventError
                        eventError = null
                        if (recovered != null) {
                            _state.update { current ->
                                if (current.error == recovered) {
                                    current.copy(error = lifecycleErrorMessage())
                                } else {
                                    current
                                }
                            }
                        }
                        refresh.request(generation, stale = true)
                    }
                },
                snapshotChanged = {
                    if (isCurrentEvent(config, generation)) {
                        refresh.request(generation, stale = true)
                    }
                },
                eventId = { if (isCurrentEvent(config, generation)) lastEventId = it },
                disconnected = { message ->
                    if (isCurrentEvent(config, generation)) {
                        refresh.activate(config, generation)
                        eventError = message
                        _state.update {
                            it.copy(
                                snapshot = it.snapshot?.copy(transportFresh = false),
                                error = message,
                            )
                        }
                    }
                },
                unauthorized = {
                    if (isCurrentEvent(config, generation)) invalidateCredential(config)
                },
            ),
        )
        events = stream
        stream.start()
    }

    private fun stopEvents(stale: Boolean = false) {
        activeEventGeneration = null
        val stream = events
        events = null
        refresh.deactivate()
        stream?.stop()
        if (stale) _state.update { it.copy(snapshot = it.snapshot?.copy(transportFresh = false)) }
    }

    private fun markSnapshotStale(config: Config, generation: Long) {
        if (!isCurrentEvent(config, generation)) return
        _state.update { it.copy(snapshot = it.snapshot?.copy(transportFresh = false)) }
    }

    private fun isCurrentEvent(config: Config, generation: Long): Boolean =
        foreground && _state.value.config == config && activeEventGeneration == generation

    private fun invalidateCredential(config: Config) {
        voice.stop()
        delivery.bind(null)
        stopEvents(stale = true)
        connection.invalidate(config)
    }

    private fun reloadLifecycleErrors() {
        lifecycleErrors = store.loadLifecycle().errors
    }

    private fun lifecycleErrorMessage(): String? = lifecycleErrors
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = " ") { it.message }

    private fun schedulePredictionExpiry() {
        val deadline = prediction.deadlineMillis() ?: return
        predictionExpiry?.cancel()
        predictionExpiry = viewModelScope.launch(dispatcher) {
            delay((deadline - nowMillis()).coerceAtLeast(0L))
            if (prediction.deadlineMillis() == deadline) {
                _state.update(prediction::fail)
                refresh.request()
            }
        }
    }

    private fun predictionReconciled() {
        if (prediction.isPending()) return
        predictionExpiry?.cancel()
        predictionExpiry = null
    }

    override fun onCleared() {
        delivery.bind(null)
        voice.close()
        predictionExpiry?.cancel()
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
