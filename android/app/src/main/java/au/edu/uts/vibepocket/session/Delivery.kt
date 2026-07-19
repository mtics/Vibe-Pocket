package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.control.Command
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class Delivery(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val client: Client,
    private val pending: Pending,
    private val config: () -> Config?,
    private val publishPending: () -> Unit,
    private val accepted: (Config) -> Unit,
    private val rejected: (Config, Throwable) -> Unit,
) {
    private data class Outbound(
        val command: Command,
        val id: String,
        val config: Config,
        val family: String?,
        val epoch: Long,
    )

    private val lock = Any()
    private val queue = ArrayDeque<Outbound>()
    private var draining = false
    private var active: Outbound? = null
    private var epoch = 0L
    private var drainJob: Job? = null

    fun send(command: Command, id: String, family: String? = null): Boolean {
        val currentConfig = config() ?: return false
        val shouldLaunch = synchronized(lock) {
            if (!pending.add(id)) return false
            if (family != null) coalesce(family)
            queue.addLast(Outbound(command, id, currentConfig, family, epoch))
            (!draining).also { launch -> if (launch) draining = true }
        }
        publishPending()
        if (shouldLaunch) drain()
        return true
    }

    fun cancel() {
        val cancelled = synchronized(lock) {
            epoch += 1
            val ids = queue.mapTo(mutableSetOf()) { it.id }
            active?.id?.let(ids::add)
            queue.clear()
            active = null
            draining = false
            val worker = drainJob
            drainJob = null
            worker to ids
        }
        cancelled.first?.cancel()
        cancelled.second.forEach(pending::remove)
        publishPending()
    }

    private fun coalesce(family: String) {
        for (index in queue.lastIndex downTo 0) {
            val queued = queue[index]
            if (queued.family == null) return
            if (queued.family == family) {
                queue.removeAt(index)
                pending.remove(queued.id)
                return
            }
        }
    }

    private fun drain() {
        val workerEpoch = synchronized(lock) { epoch }
        val worker = scope.launch(dispatcher) {
            try {
                while (currentCoroutineContext().isActive) {
                    val outbound = synchronized(lock) {
                        if (workerEpoch != epoch || queue.isEmpty()) {
                            null
                        } else {
                            queue.removeFirst().also { active = it }
                        }
                    } ?: return@launch
                    val result = runCatching { client.command(outbound.config, outbound.command) }
                    val current = synchronized(lock) {
                        val matches = outbound.epoch == epoch
                        if (active === outbound) active = null
                        matches
                    }
                    if (current) {
                        result.onSuccess { accepted(outbound.config) }
                            .onFailure { rejected(outbound.config, it) }
                    }
                    pending.remove(outbound.id)
                    if (current && config() == outbound.config) publishPending()
                }
            } finally {
                synchronized(lock) {
                    if (workerEpoch == epoch) {
                        draining = false
                        drainJob = null
                    }
                }
            }
        }
        synchronized(lock) {
            if (workerEpoch == epoch && draining) drainJob = worker else worker.cancel()
        }
    }
}
