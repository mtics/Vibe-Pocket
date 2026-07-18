package au.edu.uts.vibepocket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

interface PocketClient {
    suspend fun snapshot(config: ConnectionConfig): PocketSnapshot
    suspend fun command(config: ConnectionConfig, command: PocketCommand)
}

class PocketBridgeClient : PocketClient {
    override suspend fun snapshot(config: ConnectionConfig): PocketSnapshot = withContext(Dispatchers.IO) {
        parsePocketSnapshot(call(config, "/v1/pocket/snapshot", "GET"))
    }

    override suspend fun command(config: ConnectionConfig, command: PocketCommand) = withContext(Dispatchers.IO) {
        call(config, "/v1/pocket/commands", "POST", command.toJson())
        Unit
    }

    private fun call(
        config: ConnectionConfig,
        path: String,
        method: String,
        body: JSONObject? = null,
    ): JSONObject {
        val connection = (URL(config.normalizedUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 75_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.token}")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Idempotency-Key", UUID.randomUUID().toString())
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
        }
        val status = connection.responseCode
        val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use(BufferedReader::readText)
            .orEmpty()
        connection.disconnect()
        if (status !in 200..299) {
            val detail = runCatching {
                JSONObject(response).getJSONObject("error").getString("message")
            }.getOrDefault("The Vibe Pocket bridge rejected this action.")
            throw BridgeException(detail)
        }
        return runCatching { JSONObject(response) }.getOrElse {
            throw BridgeException("The Vibe Pocket bridge returned an invalid response.")
        }
    }
}

class PocketEventStream(
    private val config: ConnectionConfig,
    lastEventId: String?,
    private val onConnected: () -> Unit,
    private val onSnapshotChanged: () -> Unit,
    private val onEventId: (String) -> Unit,
    private val onDisconnected: (String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var currentLastEventId = lastEventId
    private val executor = Executors.newSingleThreadExecutor()

    fun start() {
        if (running) return
        running = true
        executor.execute {
            var retryDelay = 1_000L
            while (running) {
                val result = runCatching { consume() }
                if (result.isSuccess) retryDelay = 1_000L
                if (running && result.isFailure) {
                    onDisconnected(result.exceptionOrNull()?.message ?: "Connection lost.")
                }
                if (running) {
                    runCatching { Thread.sleep(retryDelay) }
                    retryDelay = (retryDelay * 2).coerceAtMost(8_000L)
                }
            }
        }
    }

    fun stop() {
        running = false
        connection?.disconnect()
        connection = null
        executor.shutdownNow()
    }

    private fun consume() {
        val stream = (URL(config.normalizedUrl + "/v1/pocket/events").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 0
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer ${config.token}")
            currentLastEventId?.takeIf(String::isNotBlank)?.let { setRequestProperty("Last-Event-ID", it) }
        }
        connection = stream
        if (stream.responseCode != HttpURLConnection.HTTP_OK) {
            stream.disconnect()
            throw BridgeException("The controller event connection was rejected.")
        }
        onConnected()
        var event = ""
        var eventId = ""
        stream.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (!running) return@forEach
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("id:") -> eventId = line.removePrefix("id:").trim()
                    line.isBlank() -> {
                        eventId.takeIf(String::isNotBlank)?.let {
                            currentLastEventId = it
                            onEventId(it)
                        }
                        if (event == "snapshot_changed") onSnapshotChanged()
                        event = ""
                        eventId = ""
                    }
                }
            }
        }
        stream.disconnect()
        connection = null
    }
}

internal fun parsePocketSnapshot(root: JSONObject): PocketSnapshot {
    val status = root.optJSONObject("status") ?: JSONObject()
    val statusMessage = status.safeString("message")
    val controls = root.optJSONObject("controls") ?: JSONObject()
    return PocketSnapshot(
        revision = root.safeString("revision") ?: "r_0",
        status = BridgeStatus(
            state = status.safeString("state") ?: "degraded",
            message = statusMessage,
        ),
        controls = DesktopControls(
            voice = controls.optBoolean("voice", false),
            stop = controls.optBoolean("stop", false),
            newTask = controls.optBoolean("new-task", false),
            approve = controls.optBoolean("approve", false),
            reject = controls.optBoolean("reject", false),
            clearInput = controls.optBoolean("clear-input", false),
            focusAgent = controls.optBoolean("focus-agent", false),
            modeCycle = controls.optBoolean("mode-cycle", false),
            accessCycle = controls.optBoolean("access-cycle", false),
            navigate = controls.optBoolean("navigate", false),
            reasoning = controls.optBoolean("reasoning", false),
            workflow = controls.optBoolean("workflow", false),
        ),
        controller = parseController(root.optJSONObject("controller")),
    )
}

private fun parseController(value: JSONObject?): ControllerState? {
    value ?: return null
    val profile = parseProfile(value.optJSONObject("profile"))
    val agents = value.optJSONArray("agents").objects().mapNotNull { agent ->
        val id = agent.safeString("id")?.takeIf(AgentIdPattern::matches) ?: return@mapNotNull null
        val label = agent.safeString("label")?.take(64) ?: return@mapNotNull null
        AgentStatus(
            id = id,
            label = label,
            state = TaskState.fromWire(agent.safeString("state").orEmpty()),
            focused = agent.optBoolean("focused", false),
        )
    }.take(6)
    val focused = value.optInt("focusedAgentIndex", -1).takeIf { it in agents.indices } ?: -1
    val focusedAgentId = value.safeString("focusedAgentId")
        ?.takeIf(AgentIdPattern::matches)
        ?.takeIf { id -> agents.any { it.id == id } }
        ?: agents.singleOrNull { it.focused }?.id
    return ControllerState(
        profile = profile,
        gestures = parseGestures(value.optJSONArray("gestures")),
        actionCatalog = parseActionCatalog(value.optJSONArray("actionCatalog")),
        activeLayerId = value.safeString("activeLayerId")
            ?.takeIf { id -> profile?.layers?.any { it.id == id } == true },
        desktopFocused = value.optBoolean("foreground", false),
        taskState = TaskState.fromWire(value.safeString("taskState").orEmpty()),
        agents = agents,
        focusedAgentIndex = focused,
        focusedAgentId = focusedAgentId,
        voice = parseVoice(value.optJSONObject("voice")),
        mode = parseSelector(value.optJSONObject("mode")),
        access = parseSelector(value.optJSONObject("access")),
        reasoning = parseReasoning(value.optJSONObject("reasoning")),
        userInput = parseCodexQuestion(value.optJSONObject("userInput")),
    )
}

private fun parseCodexQuestion(value: JSONObject?): CodexQuestion? {
    value ?: return null
    val count = value.optInt("questionCount", 0).takeIf { it in 1..3 } ?: return null
    val index = value.optInt("questionIndex", -1).takeIf { it in 0 until count } ?: return null
    val header = value.safeString("header")?.take(64) ?: return null
    val question = value.safeString("question")?.take(2_000) ?: return null
    val options = value.optJSONArray("options").objects().mapNotNull { option ->
        val label = option.safeString("label")?.take(120) ?: return@mapNotNull null
        CodexQuestionOption(label, option.safeString("description")?.take(500).orEmpty())
    }.take(8)
    val selected = value.optInt("selectedOptionIndex", -1)
        .takeIf { it in options.indices }
        ?: -1
    return CodexQuestion(
        questionIndex = index,
        questionCount = count,
        header = header,
        question = question,
        options = options,
        selectedOptionIndex = selected,
        hasSpokenAnswer = value.optBoolean("hasSpokenAnswer", false),
        isSecret = value.optBoolean("isSecret", false),
    )
}

private fun parseProfile(value: JSONObject?): ControllerProfile? {
    value ?: return null
    val inputs = value.optJSONArray("inputs").objects().mapNotNull { input ->
        val id = input.safeString("id")?.take(64) ?: return@mapNotNull null
        ControllerInput(
            id = id,
            kind = InputKind.fromWire(input.safeString("kind").orEmpty()),
            label = input.safeString("label")?.take(40) ?: id,
            icon = input.safeString("icon")?.take(24).orEmpty(),
        )
    }.distinctBy(ControllerInput::id)
    val workflows = value.optJSONArray("workflows").objects().mapNotNull { workflow ->
        val id = workflow.safeString("id")?.take(64) ?: return@mapNotNull null
        ControllerWorkflow(
            id = id,
            label = workflow.safeString("label")?.take(40) ?: id,
            prompt = workflow.safeString("prompt")?.take(4_000).orEmpty(),
        )
    }.distinctBy(ControllerWorkflow::id)
    val layers = value.optJSONArray("layers").objects().mapNotNull { layer ->
        val id = layer.safeString("id")?.take(64) ?: return@mapNotNull null
        val bindingsObject = layer.optJSONObject("bindings")
        val bindings = buildMap {
            bindingsObject?.keys()?.forEach { inputId ->
                val bindingObject = bindingsObject.optJSONObject(inputId) ?: return@forEach
                val actions = buildMap {
                    parseControllerAction(bindingObject)?.let { put(ControllerGesture.TAP, it) }
                    ControllerGesture.entries.forEach { gesture ->
                        parseControllerAction(bindingObject.optJSONObject(gesture.wireValue))?.let { put(gesture, it) }
                    }
                }
                if (actions.isNotEmpty()) put(inputId.take(64), BindingDescriptor(actions))
            }
        }
        ControllerLayer(
            id = id,
            name = layer.safeString("name")?.take(40) ?: id,
            color = layer.safeString("color")?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) },
            bindings = bindings,
        )
    }.distinctBy(ControllerLayer::id).take(6)
    if (inputs.isEmpty() || layers.isEmpty()) return null
    return ControllerProfile(
        version = value.optInt("version", 1).coerceAtLeast(1),
        inputs = inputs,
        workflows = workflows,
        layers = layers,
    )
}

private fun parseGestures(value: JSONArray?): List<GestureOption> {
    val parsed = value.objects().mapNotNull { option ->
        val gesture = ControllerGesture.fromWire(option.safeString("id").orEmpty()) ?: return@mapNotNull null
        GestureOption(gesture, option.safeString("label")?.take(24) ?: gesture.wireValue)
    }.distinctBy(GestureOption::gesture)
    return parsed.ifEmpty {
        listOf(
            GestureOption(ControllerGesture.TAP, "Tap"),
            GestureOption(ControllerGesture.DOUBLE_TAP, "Double tap"),
            GestureOption(ControllerGesture.HOLD, "Hold"),
        )
    }
}

private fun parseActionCatalog(value: JSONArray?): List<ActionCatalogEntry> = value.objects().mapNotNull { entry ->
    val id = entry.safeString("id")?.take(64) ?: return@mapNotNull null
    val action = parseControllerAction(entry.optJSONObject("action")) ?: return@mapNotNull null
    ActionCatalogEntry(id, entry.safeString("label")?.take(48) ?: id, action)
}.distinctBy(ActionCatalogEntry::id).take(64)

private fun parseControllerAction(value: JSONObject?): ControllerAction? {
    value ?: return null
    val type = value.safeString("type") ?: return null
    return when (type) {
        "approve", "reject", "voice", "new_task", "stop", "mode_cycle", "access_cycle", "clear_input", "focus_next", "attach" ->
            ControllerAction(type)
        "navigate" -> value.safeString("direction")
            ?.takeIf { it in setOf("up", "down", "left", "right") }
            ?.let { ControllerAction(type, direction = it) }
        "reasoning_depth" -> value.optInt("delta", 0)
            .takeIf { it == -1 || it == 1 }
            ?.let { ControllerAction(type, delta = it) }
        "focus_agent" -> value.optInt("index", -1)
            .takeIf { it in 0..5 }
            ?.let { ControllerAction(type, index = it) }
        "select_layer" -> value.safeString("layerId")
            ?.take(64)
            ?.let { ControllerAction(type, layerId = it) }
        "workflow" -> value.safeString("workflowId")
            ?.take(64)
            ?.let { ControllerAction(type, workflowId = it) }
        else -> null
    }
}

private fun parseSelector(value: JSONObject?): SelectorStatus = SelectorStatus(
    available = value?.optBoolean("available", false) == true,
    label = value?.safeString("label")?.take(64).orEmpty(),
)

private fun parseReasoning(value: JSONObject?): ReasoningStatus {
    val available = value?.optBoolean("available", false) == true
    return ReasoningStatus(
        available = available,
        label = value?.safeString("label")?.take(64).orEmpty(),
        level = ReasoningLevel.fromWire(value?.safeString("level")),
        // These fields were added after protocol v5. Defaulting to the overall
        // capability keeps a rolling bridge/app upgrade interactive.
        canIncrease = available && value.optBoolean("canIncrease", true),
        canDecrease = available && value.optBoolean("canDecrease", true),
    )
}

private fun parseVoice(value: JSONObject?): VoiceStatus? = value?.let {
    VoiceStatus(
        available = it.optBoolean("available", false),
        active = it.optBoolean("active", false),
    )
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) optJSONObject(index)?.let(::add)
    }
}

private fun JSONObject.safeString(key: String): String? =
    optString(key, "").trim().takeUnless { it.isEmpty() || it == "null" }

internal fun PocketCommand.toJson(): JSONObject = when (this) {
    is PocketCommand.Binding -> JSONObject()
        .put("kind", "binding")
        .put("inputId", inputId)
        .put("gesture", gesture.wireValue)
    is PocketCommand.SelectLayer -> JSONObject().put("kind", "select_layer").put("layerId", layerId)
    is PocketCommand.FocusAgent -> JSONObject().put("kind", "focus_agent").put("agentId", agentId)
    is PocketCommand.UpdateBinding -> JSONObject()
        .put("kind", "update_binding")
        .put("layerId", layerId)
        .put("inputId", inputId)
        .put("gesture", gesture.wireValue)
        .put("action", action.toJson())
    is PocketCommand.ClearBinding -> JSONObject()
        .put("kind", "clear_binding")
        .put("layerId", layerId)
        .put("inputId", inputId)
        .put("gesture", gesture.wireValue)
    is PocketCommand.RenameLayer -> JSONObject()
        .put("kind", "rename_layer")
        .put("layerId", layerId)
        .put("name", name)
    is PocketCommand.UpdateLayerColor -> JSONObject()
        .put("kind", "update_layer_color")
        .put("layerId", layerId)
        .put("color", color)
    is PocketCommand.UpdateWorkflowPrompt -> JSONObject()
        .put("kind", "update_workflow")
        .put("workflowId", workflowId)
        .put("prompt", prompt)
    PocketCommand.ResetProfile -> JSONObject().put("kind", "reset_profile")
    PocketCommand.Attach -> JSONObject().put("kind", "attach")
    PocketCommand.Voice -> JSONObject().put("kind", "voice")
    PocketCommand.VoiceStart -> JSONObject().put("kind", "voice_start")
    PocketCommand.VoiceStop -> JSONObject().put("kind", "voice_stop")
    PocketCommand.Stop -> JSONObject().put("kind", "stop")
    PocketCommand.NewTask -> JSONObject().put("kind", "new_task")
    PocketCommand.Approve -> JSONObject().put("kind", "approve")
    PocketCommand.Reject -> JSONObject().put("kind", "reject")
}
