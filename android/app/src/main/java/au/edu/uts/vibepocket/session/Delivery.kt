package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.control.Command
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
    private val pending: Pending,
    private val publishPending: () -> Unit,
    private val accepted: (Config) -> Unit,
    private val rejected: (Config, Throwable) -> Unit,
) {
    private data class Owner(
        val config: Config?,
        val epoch: Long,
    )

    private data class Outbound(
        val command: Command,
        val id: String,
        val owner: Owner,
        val family: String?,
    )

    private val lock = Any()
    private val queue = ArrayDeque<Outbound>()
    private var owner = Owner(config = null, epoch = 0)
    private var active: Outbound? = null
    private var worker: Job? = null

    fun bind(config: Config?) {
        replaceOwner(config)
    }

    fun send(command: Command, id: String, family: String? = null): Boolean {
        val shouldLaunch = synchronized(lock) {
            val currentOwner = owner.takeIf { it.config != null } ?: return false
            if (!pending.add(id)) return false
            if (family != null) coalesce(family, currentOwner)
            queue.addLast(Outbound(command, id, currentOwner, family))
            worker == null
        }
        publishPending()
        if (shouldLaunch) launchWorker()
        return true
    }

    fun cancel() {
        val currentConfig = synchronized(lock) { owner.config }
        replaceOwner(currentConfig)
    }

    private fun replaceOwner(config: Config?) {
        val cancelled = synchronized(lock) {
            owner = Owner(config, owner.epoch + 1)
            val ids = queue.mapTo(mutableSetOf()) { it.id }
            active?.id?.let(ids::add)
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
                pending.remove(queued.id)
                return
            }
        }
    }

    private fun launchWorker() {
        lateinit var candidate: Job
        candidate = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            drain()
        }
        val installed = synchronized(lock) {
            if (worker == null && queue.isNotEmpty()) {
                worker = candidate
                true
            } else {
                false
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
            val outbound = synchronized(lock) {
                queue.removeFirstOrNull()?.also { active = it }
            } ?: return
            var result: Result<Unit>? = null
            try {
                currentCoroutineContext().ensureActive()
                result = runCatching { client.command(requireNotNull(outbound.owner.config), outbound.command) }
            } finally {
                val current = synchronized(lock) {
                    if (active === outbound) active = null
                    owner == outbound.owner
                }
                pending.remove(outbound.id)
                if (current) {
                    result?.onSuccess { accepted(requireNotNull(outbound.owner.config)) }
                        ?.onFailure { rejected(requireNotNull(outbound.owner.config), it) }
                    publishPending()
                }
            }
        }
    }

    private fun workerFinished(completed: Job) {
        val shouldLaunch = synchronized(lock) {
            if (worker !== completed) {
                false
            } else {
                worker = null
                active = null
                queue.isNotEmpty()
            }
        }
        if (shouldLaunch) launchWorker()
    }
}
