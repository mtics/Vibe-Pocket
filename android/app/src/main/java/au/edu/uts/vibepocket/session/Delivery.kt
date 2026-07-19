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
    restored: LoadOutcome<PendingCommand>,
    private val pending: Pending,
    private val publishPending: () -> Unit,
    private val accepted: (Config) -> Unit,
    private val rejected: (Config, Throwable) -> Unit,
    private val unconfirmed: (Config, Throwable?) -> Unit,
    private val operationId: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Owner(
        val config: Config?,
        val epoch: Long,
    )

    private data class Outbound(
        val command: Command,
        val uiId: String,
        val owner: Owner,
        val family: String?,
    )

    private val lock = Any()
    private val queue = ArrayDeque<Outbound>()
    private var owner = Owner(config = null, epoch = 0)
    private var active: Outbound? = null
    private var outbox = (restored as? LoadOutcome.Loaded)?.value
    private var outboxUnreadable = restored is LoadOutcome.RecoverableError
    private var worker: Job? = null
    private var launchRequested = false

    init {
        outbox?.uiId?.let(pending::add)
    }

    fun bind(config: Config?) {
        replaceOwner(config)
        if (config != null) requestWorker()
    }

    fun recover() {
        val canRecover = synchronized(lock) {
            owner.config != null && (outbox != null || outboxUnreadable)
        }
        if (canRecover) requestWorker()
    }

    fun hasPendingResult(): Boolean = synchronized(lock) {
        outbox != null || outboxUnreadable
    }

    fun send(command: Command, id: String, family: String? = null): Boolean {
        val shouldLaunch = synchronized(lock) {
            val currentOwner = owner.takeIf { it.config != null } ?: return false
            if (outboxUnreadable || outbox != null && active?.owner != currentOwner) return false
            if (!pending.add(id)) return false
            if (family != null) coalesce(family, currentOwner)
            queue.addLast(Outbound(command, id, currentOwner, family))
            worker == null
        }
        publishPending()
        if (shouldLaunch) requestWorker()
        return true
    }

    fun cancel() {
        val currentConfig = synchronized(lock) { owner.config }
        replaceOwner(currentConfig)
    }

    private fun replaceOwner(config: Config?) {
        val cancelled = synchronized(lock) {
            owner = Owner(config, owner.epoch + 1)
            val durableUiId = outbox?.uiId
            val ids = queue.mapTo(mutableSetOf()) { it.uiId }
            active?.uiId?.takeIf { it != durableUiId }?.let(ids::add)
            queue.clear()
            worker to ids
        }
        cancelled.first?.cancel()
        cancelled.second.forEach(pending::remove)
        publishPending()
    }

    private fun coalesce(family: String, currentOwner: Owner) {
        for (index in queue.lastIndex downTo 0) {
            val queued = queue[index]
            if (queued.owner != currentOwner || queued.family == null) return
            if (queued.family == family) {
                queue.removeAt(index)
                pending.remove(queued.uiId)
                return
            }
        }
    }

    private fun requestWorker() {
        val candidate = scope.launch(dispatcher, start = CoroutineStart.LAZY) { drain() }
        val installed = synchronized(lock) {
            val hasWork = queue.isNotEmpty() || outbox != null || outboxUnreadable
            when {
                owner.config == null || !hasWork -> false
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

    private suspend fun drain() {
        while (currentCoroutineContext().isActive) {
            if (isOutboxUnreadable()) {
                if (!reloadOutbox()) return
                continue
            }

            val persisted = synchronized(lock) { outbox }
            if (persisted != null) {
                if (!queryAndSettle(persisted)) return
                continue
            }

            val outbound = synchronized(lock) {
                queue.removeFirstOrNull()?.also { active = it }
            } ?: return
            if (!dispatch(outbound)) return
        }
    }

    private fun isOutboxUnreadable(): Boolean = synchronized(lock) { outboxUnreadable }

    private fun reloadOutbox(): Boolean {
        return when (val loaded = store.loadPendingCommandRecord()) {
            LoadOutcome.Absent -> {
                synchronized(lock) { outboxUnreadable = false }
                publishPending()
                true
            }
            is LoadOutcome.Loaded -> {
                synchronized(lock) {
                    outbox = loaded.value
                    outboxUnreadable = false
                }
                pending.add(loaded.value.uiId)
                publishPending()
                true
            }
            is LoadOutcome.RecoverableError -> {
                publishPending()
                false
            }
        }
    }

    private suspend fun dispatch(outbound: Outbound): Boolean {
        val config = requireNotNull(outbound.owner.config)
        val persisted = try {
            PendingCommand(config, operationId(), outbound.uiId).also(store::savePendingCommand)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failBeforeDispatch(outbound, error)
            return false
        }
        synchronized(lock) { outbox = persisted }
        publishPending()

        currentCoroutineContext().ensureActive()
        return try {
            client.command(config, outbound.command, persisted.operationId)
            currentCoroutineContext().ensureActive()
            settle(persisted) { accepted(config) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            queryAndSettle(persisted)
        } catch (error: Throwable) {
            settle(persisted) { rejected(config, error) }
        }
    }

    private suspend fun queryAndSettle(persisted: PendingCommand): Boolean {
        val result = try {
            client.commandResult(persisted.config, persisted.operationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            retainUnconfirmed(persisted, null)
            return false
        }

        return when (result) {
            is CommandResult.Found -> when (result.status) {
                CommandStatus.ACCEPTED,
                CommandStatus.RUNNING,
                -> {
                    retainUnconfirmed(persisted, null)
                    false
                }
                CommandStatus.SUCCEEDED -> settle(persisted) { accepted(persisted.config) }
                CommandStatus.FAILED -> settle(persisted) {
                    rejected(
                        persisted.config,
                        Failure(
                            result.error?.message ?: "The Bridge reported that the command failed.",
                            errorCode = result.error?.code,
                        ),
                    )
                }
                CommandStatus.UNKNOWN -> settle(persisted) {
                    rejected(
                        persisted.config,
                        Failure(
                            result.error?.message
                                ?: "The Bridge can no longer determine this command's result.",
                            errorCode = result.error?.code,
                        ),
                    )
                }
            }
            CommandResult.NotFound -> settle(persisted) {
                rejected(
                    persisted.config,
                    Failure("The Bridge did not receive this command."),
                )
            }
        }
    }

    private fun settle(persisted: PendingCommand, notify: () -> Unit): Boolean {
        val clearError = runCatching {
            check(store.clearPendingCommand(persisted.operationId)) {
                "Vibe Pocket found a different pending command while clearing the outbox."
            }
        }.exceptionOrNull()
        if (clearError != null) {
            retainUnconfirmed(persisted, clearError)
            return false
        }

        synchronized(lock) {
            if (outbox?.operationId == persisted.operationId) outbox = null
            active = null
        }
        pending.remove(persisted.uiId)
        notify()
        publishPending()
        return true
    }

    private fun failBeforeDispatch(outbound: Outbound, error: Throwable) {
        val dropped = synchronized(lock) {
            active = null
            queue.mapTo(mutableSetOf()) { it.uiId }.also { queue.clear() }
        }
        dropped.forEach(pending::remove)
        pending.remove(outbound.uiId)
        rejected(requireNotNull(outbound.owner.config), error)
        publishPending()
    }

    private fun retainUnconfirmed(persisted: PendingCommand, error: Throwable?) {
        val dropped = synchronized(lock) {
            active = null
            queue.mapTo(mutableSetOf()) { it.uiId }.also { queue.clear() }
        }
        dropped.forEach(pending::remove)
        unconfirmed(persisted.config, error)
        publishPending()
    }

    private fun workerFinished(completed: Job) {
        val shouldLaunch = synchronized(lock) {
            if (worker !== completed) return
            worker = null
            active = null
            val requested = launchRequested
            launchRequested = false
            owner.config != null && (
                requested || queue.isNotEmpty() && outbox == null && !outboxUnreadable
            )
        }
        if (shouldLaunch) requestWorker()
    }
}
