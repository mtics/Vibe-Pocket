package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.bridge.CommandResult
import au.edu.uts.vibepocket.bridge.CommandStatus
import au.edu.uts.vibepocket.bridge.Failure
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.LoadOutcome
import au.edu.uts.vibepocket.connection.PendingCommand
import au.edu.uts.vibepocket.connection.Store
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.ContextTransition
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class Delivery(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val client: Client,
    private val store: Store,
    restored: LoadOutcome<List<PendingCommand>>,
    private val pending: Pending,
    private val publishPending: () -> Unit,
    private val accepted: (PendingCommand) -> Unit,
    private val rejected: (Config, String, PendingCommand?, Throwable) -> Unit,
    private val unconfirmed: (PendingCommand, Throwable?) -> Unit,
    private val operationId: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Owner(
        val config: Config?,
        val epoch: Long,
    )

    private val lock = Any()
    private var owner = Owner(config = null, epoch = 0)
    private val outbox = ArrayDeque((restored as? LoadOutcome.Loaded)?.value.orEmpty())
    private var outboxUnreadable =
        restored is LoadOutcome.RecoverableError && !restored.settled
    private var worker: Job? = null
    private var launchRequested = false

    init {
        outbox.forEach { pending.add(it.uiId) }
    }

    fun bind(config: Config?) {
        replaceOwner(config)
        if (config != null) requestWorker()
    }

    fun recover() {
        if (synchronized(lock) { canWorkLocked() }) requestWorker()
    }

    fun hasPendingResult(): Boolean = synchronized(lock) {
        outbox.isNotEmpty() || outboxUnreadable
    }

    @Suppress("UNUSED_PARAMETER")
    fun send(
        command: Command,
        id: String,
        family: String? = null,
        transition: ContextTransition? = null,
    ): Boolean {
        var saveError: Throwable? = null
        var saveConfig: Config? = null
        val saved = synchronized(lock) {
            val currentOwner = owner.takeIf { it.config != null } ?: return@synchronized false
            if (outboxUnreadable) return@synchronized false
            if (transition != null && outbox.isNotEmpty()) return@synchronized false
            if (outbox.any { it.transition != null }) return@synchronized false
            if (!pending.add(id)) return@synchronized false

            val persisted = try {
                PendingCommand(
                    config = requireNotNull(currentOwner.config),
                    command = command,
                    operationId = operationId(),
                    uiId = id,
                    transition = transition,
                )
            } catch (error: Throwable) {
                pending.remove(id)
                saveConfig = currentOwner.config
                saveError = error
                return@synchronized false
            }

            val reserved = try {
                store.trySavePendingCommand(persisted)
            } catch (error: Throwable) {
                pending.remove(id)
                saveConfig = currentOwner.config
                saveError = error
                return@synchronized false
            }
            if (!reserved) {
                pending.remove(id)
                return@synchronized false
            }

            outbox.addLast(persisted)
            true
        }

        if (!saved) {
            saveError?.let { rejected(requireNotNull(saveConfig), id, null, it) }
            publishPending()
            return false
        }

        publishPending()
        requestWorker()
        return true
    }

    fun cancel() {
        val currentConfig = synchronized(lock) { owner.config }
        replaceOwner(currentConfig)
    }

    fun retireOutbox() {
        val uiIds = synchronized(lock) {
            outbox.map(PendingCommand::uiId).also {
                outbox.clear()
                outboxUnreadable = false
                launchRequested = false
            }
        }
        uiIds.forEach(pending::remove)
        publishPending()
    }

    fun clearRetainedTransition(command: PendingCommand): Boolean {
        val retained = synchronized(lock) {
            outbox.firstOrNull()?.takeIf {
                it.operationId == command.operationId &&
                    it.uiId == command.uiId &&
                    it.transition == command.transition &&
                    it.transition != null
            }
        } ?: return false
        val clearError = runCatching {
            check(store.clearPendingCommand(retained.operationId)) {
                "Vibe Pocket found a different pending command while clearing the outbox."
            }
        }.exceptionOrNull()
        if (clearError != null) {
            retain(retained, clearError)
            return false
        }
        synchronized(lock) {
            if (outbox.firstOrNull()?.operationId == retained.operationId) outbox.removeFirst()
        }
        pending.remove(retained.uiId)
        publishPending()
        requestWorker()
        return true
    }

    private fun replaceOwner(config: Config?) {
        val cancelled = synchronized(lock) {
            owner = Owner(config, owner.epoch + 1)
            if (config == null) launchRequested = false
            worker
        }
        cancelled?.cancel()
        publishPending()
    }

    private fun requestWorker() {
        val candidate = scope.launch(dispatcher, start = CoroutineStart.LAZY) { drain() }
        val installed = synchronized(lock) {
            when {
                !canWorkLocked() -> false
                worker != null -> {
                    launchRequested = true
                    false
                }
                else -> {
                    worker = candidate
                    true
                }
            }
        }
        if (!installed) {
            candidate.cancel()
            return
        }
        candidate.invokeOnCompletion { workerFinished(candidate) }
        candidate.start()
    }

    private fun canWorkLocked(): Boolean {
        val current = owner.config ?: return false
        if (outboxUnreadable) return true
        return outbox.firstOrNull()?.config == current
    }

    private suspend fun drain() {
        while (currentCoroutineContext().isActive) {
            if (isOutboxUnreadable()) {
                if (!reloadOutbox()) return
                continue
            }

            val work = synchronized(lock) {
                val persisted = outbox.firstOrNull() ?: return
                if (owner.config != persisted.config) return
                persisted
            }
            val settled = when (work.phase) {
                PendingCommand.Phase.PREPARED -> dispatchAndSettle(work)
                PendingCommand.Phase.DISPATCH_ATTEMPTED ->
                    queryAndSettle(work, replayNotFound = false)
            }
            if (!settled) return
        }
    }

    private fun isOutboxUnreadable(): Boolean = synchronized(lock) { outboxUnreadable }

    private fun reloadOutbox(): Boolean {
        return when (val loaded = store.loadPendingCommandsRecord()) {
            LoadOutcome.Absent -> {
                synchronized(lock) {
                    outbox.clear()
                    outboxUnreadable = false
                }
                publishPending()
                true
            }
            is LoadOutcome.Loaded -> {
                synchronized(lock) {
                    outbox.clear()
                    outbox.addAll(loaded.value)
                    outboxUnreadable = false
                }
                loaded.value.forEach { pending.add(it.uiId) }
                publishPending()
                true
            }
            is LoadOutcome.RecoverableError -> {
                if (loaded.settled) synchronized(lock) { outboxUnreadable = false }
                publishPending()
                false
            }
        }
    }

    private suspend fun dispatchAndSettle(prepared: PendingCommand): Boolean {
        val persisted = try {
            store.markPendingCommandDispatched(prepared.operationId)
        } catch (error: Throwable) {
            retain(prepared, error)
            return false
        }
        synchronized(lock) {
            if (outbox.firstOrNull()?.operationId != prepared.operationId) return false
            outbox.removeFirst()
            outbox.addFirst(persisted)
        }
        currentCoroutineContext().ensureActive()
        return try {
            client.command(persisted.config, persisted.command, persisted.operationId)
            currentCoroutineContext().ensureActive()
            completeSuccess(persisted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            queryAndSettle(persisted, replayNotFound = false)
        } catch (error: Throwable) {
            if ((error as? Failure)?.errorCode == CommandOutcomeIndeterminate) {
                settleAmbiguous(persisted)
            } else {
                settle(persisted) {
                    rejected(persisted.config, persisted.uiId, persisted, error)
                }
            }
        }
    }

    private suspend fun queryAndSettle(
        persisted: PendingCommand,
        replayNotFound: Boolean,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val result = try {
            client.commandResult(persisted.config, persisted.operationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            retain(persisted, null)
            return false
        }

        return when (result) {
            is CommandResult.Found -> when (result.status) {
                CommandStatus.ACCEPTED,
                CommandStatus.RUNNING,
                -> {
                    retain(persisted, null)
                    false
                }
                CommandStatus.SUCCEEDED -> completeSuccess(persisted)
                CommandStatus.FAILED -> {
                    if (result.error?.code == CommandOutcomeIndeterminate) {
                        settleAmbiguous(persisted)
                    } else {
                        val failure = Failure(
                            result.error?.message ?: "The Bridge reported that the command failed.",
                            errorCode = result.error?.code,
                        )
                        settle(persisted) {
                            rejected(persisted.config, persisted.uiId, persisted, failure)
                        }
                    }
                }
                CommandStatus.UNKNOWN -> settleAmbiguous(persisted)
            }
            CommandResult.NotFound -> {
                if (replayNotFound) {
                    dispatchAndSettle(persisted)
                } else {
                    settleAmbiguous(persisted)
                }
            }
        }
    }

    private fun settleAmbiguous(persisted: PendingCommand): Boolean {
        val failure = Failure(
            AmbiguousOutcome,
            errorCode = CommandOutcomeIndeterminate,
        )
        return settle(persisted) {
            rejected(persisted.config, persisted.uiId, persisted, failure)
        }
    }

    private fun completeSuccess(persisted: PendingCommand): Boolean {
        if (persisted.transition == null) return settle(persisted) { accepted(persisted) }
        accepted(persisted)
        publishPending()
        return false
    }

    private fun settle(persisted: PendingCommand, notify: () -> Unit): Boolean {
        val clearError = runCatching {
            check(store.clearPendingCommand(persisted.operationId)) {
                "Vibe Pocket found a different pending command while clearing the outbox."
            }
        }.exceptionOrNull()
        if (clearError != null) {
            retain(persisted, clearError)
            return false
        }

        synchronized(lock) {
            if (outbox.firstOrNull()?.operationId == persisted.operationId) outbox.removeFirst()
        }
        pending.remove(persisted.uiId)
        notify()
        publishPending()
        return true
    }

    private fun retain(persisted: PendingCommand, error: Throwable?) {
        unconfirmed(persisted, error)
        publishPending()
    }

    private fun workerFinished(completed: Job) {
        val shouldLaunch = synchronized(lock) {
            if (worker !== completed) return
            worker = null
            val requested = launchRequested
            launchRequested = false
            requested && canWorkLocked()
        }
        if (shouldLaunch) requestWorker()
    }
}

internal const val CommandOutcomeIndeterminate = "command_outcome_indeterminate"
internal const val AmbiguousOutcome =
    "The command may have completed, but its outcome could not be confirmed."
