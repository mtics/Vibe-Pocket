package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.connection.Store
import au.edu.uts.vibepocket.connection.VoiceStop
import au.edu.uts.vibepocket.control.Command
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

internal class Voice(
    dispatcher: CoroutineDispatcher,
    private val client: Client,
    private val store: Store,
    private val accepted: (Config) -> Unit,
    private val rejected: (Config, Throwable) -> Unit,
    private val retry: Retry = Retry(),
    private val key: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Owner(
        val inputId: String,
        val stop: VoiceStop,
        val generation: Long,
        var startPending: Boolean = true,
    )

    private data class Obligation(
        val stop: VoiceStop,
        val startGeneration: Long,
        var startPending: Boolean = true,
    )

    private val lock = Any()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    private var owner: Owner? = null
    private var obligation: Obligation? = store.loadVoiceStop()?.let {
        Obligation(stop = it, startGeneration = 0, startPending = false)
    }
    private var stopping = false
    private var closing = false
    private var generation = 0L

    fun restore() {
        val shouldLaunch = synchronized(lock) {
            val launch = obligation != null && !stopping
            if (launch) stopping = true
            launch
        }
        if (shouldLaunch) drainStop()
    }

    fun start(inputId: String, config: Config): Boolean {
        val request = try {
            synchronized(lock) {
                if (owner != null || obligation != null || closing) return false
                val stop = VoiceStop(config, key())
                store.saveVoiceStop(stop)
                Owner(inputId, stop, ++generation).also { owner = it }
            }
        } catch (error: Throwable) {
            rejected(config, error)
            return false
        }
        scope.launch {
            try {
                withTimeout(retry.timeoutMillis) {
                    client.command(request.stop.config, Command.VoiceStart)
                }
                accepted(request.stop.config)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                rejected(request.stop.config, error)
            }
            val shouldDrain = synchronized(lock) {
                owner?.takeIf { it.generation == request.generation }?.startPending = false
                obligation?.takeIf { it.startGeneration == request.generation }?.startPending = false
                val launch = obligation?.startGeneration == request.generation && !stopping && !closing
                if (launch) stopping = true
                launch
            }
            if (shouldDrain) drainStop()
        }
        return true
    }

    fun stop(inputId: String? = null, fallbackConfig: Config? = null): Boolean {
        val shouldLaunch = try {
            synchronized(lock) {
                if (closing) return false
                val current = owner
                if (current != null) {
                    owner = null
                    obligation = Obligation(current.stop, current.generation, current.startPending)
                } else if (obligation == null && fallbackConfig != null) {
                    val stop = VoiceStop(fallbackConfig, key())
                    store.saveVoiceStop(stop)
                    obligation = Obligation(stop, startGeneration = 0, startPending = false)
                }
                val pendingStop = obligation ?: return false
                val launch = !pendingStop.startPending && !stopping
                if (launch) stopping = true
                launch
            }
        } catch (error: Throwable) {
            fallbackConfig?.let { rejected(it, error) }
            return false
        }
        if (shouldLaunch) drainStop()
        return true
    }

    fun close() {
        synchronized(lock) {
            closing = true
            val current = owner
            if (current != null) {
                owner = null
                obligation = Obligation(current.stop, current.generation, current.startPending)
            }
        }
        job.cancel()
    }

    private fun drainStop() {
        scope.launch {
            var retryDelay = retry.initialDelayMillis
            try {
                while (currentCoroutineContext().isActive) {
                    val current = synchronized(lock) { obligation } ?: return@launch
                    val result = try {
                        withTimeout(retry.timeoutMillis) {
                            client.stopVoice(current.stop.config, current.stop.idempotencyKey)
                        }
                        Result.success(Unit)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                    if (result.isFailure) rejected(current.stop.config, requireNotNull(result.exceptionOrNull()))
                    val cleared = result.isSuccess && runCatching {
                        store.clearVoiceStop(current.stop.idempotencyKey)
                    }.onFailure {
                        rejected(current.stop.config, it)
                    }.getOrDefault(false)
                    val acknowledged = cleared && synchronized(lock) {
                        if (obligation !== current) {
                            false
                        } else {
                            obligation = null
                            true
                        }
                    }
                    if (acknowledged) {
                        accepted(current.stop.config)
                        retryDelay = retry.initialDelayMillis
                        continue
                    }
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(retry.maxDelayMillis)
                }
            } finally {
                synchronized(lock) { stopping = false }
            }
        }
    }
}
