package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.bridge.Client
import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.control.Command
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
    fun send(command: Command, id: String): Boolean {
        val currentConfig = config() ?: return false
        if (!pending.add(id)) return false
        publishPending()
        scope.launch(dispatcher) {
            runCatching { client.command(currentConfig, command) }
                .onSuccess { accepted(currentConfig) }
                .onFailure { rejected(currentConfig, it) }
            pending.remove(id)
            if (config() == currentConfig) publishPending()
        }
        return true
    }
}
