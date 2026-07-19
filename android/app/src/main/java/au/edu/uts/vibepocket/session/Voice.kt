package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.control.Command
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class Voice(
    dispatcher: CoroutineDispatcher,
    private val client: Client,
    private val accepted: (Config) -> Unit,
    private val rejected: (Config, Throwable) -> Unit,
) {
    private data class Owner(
        val inputId: String,
        val config: Config,
    )

    private data class Transition(
        val command: Command,
        val config: Config,
    )

    private val lock = Any()
    private val pending = ArrayDeque<Transition>()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    private var draining = false
    private var owner: Owner? = null
    private var closeWhenDrained = false

    fun start(inputId: String, config: Config): Boolean {
        val shouldLaunch = synchronized(lock) {
            if (owner != null) return@synchronized null
            owner = Owner(inputId, config)
            pending.addLast(Transition(Command.VoiceStart, config))
            (!draining).also { launch -> if (launch) draining = true }
        } ?: return false
        if (shouldLaunch) drain()
        return true
    }

    fun stop(inputId: String? = null): Boolean {
        val shouldLaunch = synchronized(lock) {
            val current = owner?.takeIf { inputId == null || it.inputId == inputId }
                ?: return@synchronized null
            owner = null
            pending.addLast(Transition(Command.VoiceStop, current.config))
            (!draining).also { launch -> if (launch) draining = true }
        } ?: return false
        if (shouldLaunch) drain()
        return true
    }

    fun close() {
        stop()
        val closeNow = synchronized(lock) {
            closeWhenDrained = true
            !draining && pending.isEmpty()
        }
        if (closeNow) job.cancel()
    }

    private fun drain() {
        scope.launch {
            while (true) {
                var closeScope = false
                val transition = synchronized(lock) {
                    if (pending.isEmpty()) {
                        draining = false
                        closeScope = closeWhenDrained
                        null
                    } else {
                        pending.removeFirst()
                    }
                }
                if (transition == null) {
                    if (closeScope) job.cancel()
                    return@launch
                }
                runCatching { client.command(transition.config, transition.command) }
                    .onSuccess { accepted(transition.config) }
                    .onFailure { rejected(transition.config, it) }
            }
        }
    }
}
