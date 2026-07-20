package au.edu.uts.vibepocket.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.bridge.EventCallbacks
import au.edu.uts.vibepocket.bridge.EventFactory
import au.edu.uts.vibepocket.bridge.EventStream
import au.edu.uts.vibepocket.bridge.Failure
import au.edu.uts.vibepocket.bridge.Http
import au.edu.uts.vibepocket.bridge.NetworkEvents
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.Invitation
import au.edu.uts.vibepocket.connection.LoadOutcome
import au.edu.uts.vibepocket.connection.PendingCommand
import au.edu.uts.vibepocket.connection.Store
import au.edu.uts.vibepocket.connection.loadLifecycle
import au.edu.uts.vibepocket.connection.valueOrNull
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.ContextTransition
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.conflictGroups
import au.edu.uts.vibepocket.control.contextTransition
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
    private val restoredOutbox = restored.pendingCommands.valueOrNull().orEmpty()
    private var lifecycleErrors = restored.errors
    private val legacyCommandSettled =
        (restored.pendingCommand as? LoadOutcome.RecoverableError)?.settled == true
    private val transitionLock = Any()
    private var transitionBarrier: TransitionBarrier = restored.pendingCommand.valueOrNull()
        ?.takeIf { it.transition != null }
        ?.let {
            TransitionBarrier.AwaitingDelivery(
                it.uiId,
                requireNotNull(it.transition),
                command = it,
            )
        }
        ?: TransitionBarrier.Unconfirmed
    private var transitionStateUnreadable = restored.pendingCommand.let {
        it is LoadOutcome.RecoverableError && !it.settled
    }
    private val pending = Pending().apply {
        restoredOutbox.forEach { add(it.uiId) }
    }
    private val _state = MutableStateFlow(
        State(
            inFlightIds = pending.snapshot(),
            busyGroups = restoredOutbox.flatMapTo(mutableSetOf()) { it.command.conflictGroups() },
            operation = restoredOutbox.firstOrNull()?.operation(),
            contextTransitionPending = transitionPending(),
            error = lifecycleErrorMessage()
                ?: AmbiguousOutcome.takeIf { legacyCommandSettled },
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()
    private val _feedback = MutableSharedFlow<Feedback>(extraBufferCapacity = 4)
    val feedback: SharedFlow<Feedback> = _feedback.asSharedFlow()

    @Volatile private var foreground = false
    @Volatile private var lastEventId: String? = null
    @Volatile private var eventError: String? = null
    @Volatile private var activeEventGeneration: Long? = null
    private var events: EventStream? = null
    private var eventGeneration = 0L
    private var predictionExpiry: Job? = null
    private var operationExpiry: Job? = null

    private val prediction = Prediction(nowMillis)
    private val refresh = Refresh(
        scope = viewModelScope,
        dispatcher = dispatcher,
        client = client,
        state = _state,
        reconcile = prediction::reconcile,
        reconciled = ::snapshotReconciled,
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
        store = store,
        restored = restored.pendingCommands,
        pending = pending,
        publishPending = ::publishPending,
        accepted = ::deliveryAccepted,
        rejected = ::deliveryRejected,
        unconfirmed = ::commandUnconfirmed,
        changed = ::operationChanged,
    )
    private val commands = Commands(
        snapshot = { _state.value.snapshot },
        deliver = ::enqueueCommand,
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
                    error = lifecycleErrorMessage() ?: it.error,
                )
            }
            delivery.bind(config)
            refresh.activate(config, eventGeneration)
            requestRefresh()
        } else {
            restored.pendingCommand.valueOrNull()?.config?.let(delivery::bind)
            connection.pendingInvitation?.let { invitation ->
                _state.update {
                    it.copy(invitation = invitation, error = lifecycleErrorMessage() ?: it.error)
                }
            }
        }
        voice.restore()
        connection.restore()
        _state.value.operation?.let(::scheduleOperationExpiry)
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

    fun refresh() = requestRefresh()

    fun contextTransitionPending(): Boolean = transitionPending()

    fun activateInput(
        inputId: String,
        gesture: Gesture.Kind = Gesture.Kind.TAP,
    ): Boolean = commands.activate(inputId, gesture)

    fun openModel(): Boolean = commands.openModel()

    fun startVoice(inputId: String): Boolean {
        return synchronized(transitionLock) {
            if (transitionPendingLocked()) return false
            val current = _state.value
            val config = current.config ?: return false
            val snapshot = current.snapshot ?: return false
            if (!snapshot.voiceTapEnabled(inputId)) return false
            voice.start(inputId, config)
        }
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

    fun selectMode(modeId: String): Boolean = commands.selectMode(modeId)

    fun selectReasoning(level: au.edu.uts.vibepocket.control.Reasoning.Level): Boolean =
        commands.selectReasoning(level)

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

    private fun enqueueCommand(command: Command, uiId: String): Boolean {
        return synchronized(transitionLock) {
            val snapshot = _state.value.snapshot ?: return false
            val transition = command.contextTransition(snapshot)
            if (transition != null && transitionPendingLocked()) return false
            if (
                transition != null &&
                (voice.isActive() || snapshot.desktop?.voice?.active == true)
            ) {
                return false
            }
            if (transition != null) {
                transitionBarrier = TransitionBarrier.AwaitingDelivery(uiId, transition)
                _state.update { it.copy(contextTransitionPending = true) }
            }
            val reasoningTarget = (command as? Command.SelectReasoning)?.level
            if (reasoningTarget != null) {
                _state.update { prediction.expect(it, reasoningTarget) }
            }
            val accepted = delivery.send(command, uiId, transition)
            if (!accepted) {
                if (reasoningTarget != null) _state.update(prediction::fail)
                if (transition != null) clearTransitionLocked(uiId, transition)
            } else if (reasoningTarget != null) {
                schedulePredictionExpiry()
            }
            accepted
        }
    }

    private fun connectionAccepted(config: Config, snapshot: Snapshot) {
        val previous = _state.value
        val retainedOperation = previous.operation.takeIf { previous.config == config }
        if (retainedOperation == null) {
            operationExpiry?.cancel()
            operationExpiry = null
        }
        voice.stop()
        stopEvents()
        predictionExpiry?.cancel()
        predictionExpiry = null
        prediction.clear()
        lastEventId = null
        eventError = null
        reloadLifecycleErrors()
        _state.value = State(
            config = config,
            snapshot = snapshot,
            invitation = null,
            inFlightIds = pending.snapshot(),
            busyGroups = delivery.busyGroups(),
            operation = retainedOperation,
            contextTransitionPending = transitionPending(),
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
        operationExpiry?.cancel()
        operationExpiry = null
        delivery.bind(null)
        stopEvents()
        predictionExpiry?.cancel()
        predictionExpiry = null
        prediction.clear()
        lastEventId = null
        eventError = null
        synchronized(transitionLock) {
            transitionBarrier = TransitionBarrier.Unconfirmed
            transitionStateUnreadable = false
        }
        delivery.retireOutbox()
        reloadLifecycleErrors()
        _state.value = State(
            invitation = connection.pendingInvitation,
            inFlightIds = pending.snapshot(),
            busyGroups = delivery.busyGroups(),
            contextTransitionPending = transitionPending(),
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
        if (!foreground) requestRefresh()
    }

    private fun deliveryAccepted(command: PendingCommand) {
        if (command.transition == null) {
            commandAccepted(command.config)
            return
        }
        if (_state.value.config != command.config) return
        val ownsBarrier = synchronized(transitionLock) {
            transitionBarrier = transitionBarrier.deliveryAccepted(command)
            transitionBarrier.owns(command)
        }
        if (!ownsBarrier) return
        requestRefresh(stale = true)
    }

    private fun commandRejected(config: Config, error: Throwable) {
        if (_state.value.config != config) return
        _state.update { it.copy(error = error.message) }
        _feedback.tryEmit(Feedback.Error)
    }

    private fun deliveryRejected(
        config: Config,
        uiId: String,
        command: PendingCommand?,
        error: Throwable,
    ) {
        if (uiId.startsWith("reasoning:")) {
            predictionExpiry?.cancel()
            predictionExpiry = null
            _state.update(prediction::fail)
        }
        val ambiguous = (error as? Failure)?.errorCode == CommandOutcomeIndeterminate
        synchronized(transitionLock) {
            clearTransitionLocked(uiId, command?.transition)
        }
        commandRejected(config, error)
        if (ambiguous && _state.value.config == config) requestRefresh(stale = true)
    }

    private fun commandUnconfirmed(command: PendingCommand, error: Throwable?) {
        if (_state.value.config != command.config) return
        if (error == null) return
        _state.update { it.copy(error = error.message) }
        _feedback.tryEmit(Feedback.Error)
    }

    private fun publishPending() {
        reloadLifecycleErrors()
        _state.update { current ->
            current.copy(
                inFlightIds = pending.snapshot(),
                busyGroups = delivery.busyGroups(),
                contextTransitionPending = transitionPending(),
                error = lifecycleErrorMessage() ?: current.error,
            )
        }
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
                        delivery.recover()
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
                        requestRefresh(generation, stale = true)
                    }
                },
                snapshotChanged = {
                    if (isCurrentEvent(config, generation)) {
                        delivery.recover()
                        requestRefresh(generation)
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
        val current = store.loadLifecycle()
        lifecycleErrors = current.errors
        synchronized(transitionLock) {
            val pendingCommand = current.pendingCommand
            transitionStateUnreadable = pendingCommand is LoadOutcome.RecoverableError &&
                !pendingCommand.settled
            pendingCommand.valueOrNull()?.takeIf { it.transition != null }?.let { command ->
                if (transitionBarrier == TransitionBarrier.Unconfirmed) {
                    transitionBarrier = TransitionBarrier.AwaitingDelivery(
                        command.uiId,
                        requireNotNull(command.transition),
                        command = command,
                    )
                }
            }
        }
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
                requestRefresh()
            }
        }
    }

    private fun snapshotReconciled(snapshot: Snapshot, version: Long) {
        delivery.recover()
        _state.update { it.copy(reasoningTarget = prediction.target()) }
        predictionReconciled()
        val command = synchronized(transitionLock) {
            transitionBarrier = transitionBarrier.observe(snapshot, version)
            transitionBarrier.confirmedCommand()
        } ?: return
        if (!delivery.clearRetainedTransition(command)) return
        operationChanged(command, Operation.Phase.OBSERVED, null)
        synchronized(transitionLock) {
            transitionBarrier = transitionBarrier.confirmationCleared(command)
            _state.update { it.copy(contextTransitionPending = transitionPendingLocked()) }
        }
        _feedback.tryEmit(Feedback.Success)
    }

    private fun requestRefresh(
        generation: Long? = null,
        stale: Boolean = false,
    ): Long? = refresh.request(generation, stale) { version ->
        synchronized(transitionLock) {
            transitionBarrier = transitionBarrier.observationPrepared(version)
        }
    }

    private fun predictionReconciled() {
        if (prediction.isPending()) return
        predictionExpiry?.cancel()
        predictionExpiry = null
    }

    private fun operationChanged(
        command: PendingCommand,
        phase: Operation.Phase,
        message: String?,
    ) {
        if (_state.value.config != command.config) return
        val operation = command.operation(phase, message)
        _state.update { it.copy(operation = operation) }
        scheduleOperationExpiry(operation)
    }

    private fun scheduleOperationExpiry(operation: Operation) {
        operationExpiry?.cancel()
        if (operation.phase !in ActiveOperationPhases) return
        operationExpiry = viewModelScope.launch(dispatcher) {
            delay(OperationConfirmationTimeoutMillis)
            _state.update { current ->
                val active = current.operation
                if (active?.id != operation.id || active.phase !in ActiveOperationPhases) {
                    current
                } else {
                    current.copy(
                        operation = active.copy(
                            phase = Operation.Phase.UNKNOWN,
                            message = OperationTimedOut,
                        ),
                    )
                }
            }
        }
    }

    private fun transitionPending(): Boolean = synchronized(transitionLock) {
        transitionPendingLocked()
    }

    private fun transitionPendingLocked(): Boolean = transitionBarrier.isPending || transitionStateUnreadable

    private fun clearTransitionLocked(uiId: String, target: ContextTransition?) {
        val updated = transitionBarrier.unconfirm(uiId, target)
        if (updated == transitionBarrier) return
        transitionBarrier = updated
        _state.update { it.copy(contextTransitionPending = transitionPendingLocked()) }
    }

    override fun onCleared() {
        delivery.bind(null)
        voice.close()
        predictionExpiry?.cancel()
        operationExpiry?.cancel()
        stopEvents()
        super.onCleared()
    }

    companion object {
        private val ActiveOperationPhases = setOf(
            Operation.Phase.SENT,
            Operation.Phase.ACKNOWLEDGED,
            Operation.Phase.OBSERVING,
        )
        private const val OperationConfirmationTimeoutMillis = 10_000L
        private const val OperationTimedOut =
            "The Mac has not confirmed this command. Its outcome may be unknown."

        fun create(store: Store): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = Session(store) as T
        }
    }
}
