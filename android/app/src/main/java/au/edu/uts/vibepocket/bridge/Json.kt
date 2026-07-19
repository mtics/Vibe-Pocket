package au.edu.uts.vibepocket.bridge

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Agent
import au.edu.uts.vibepocket.control.AgentId
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.Desktop
import au.edu.uts.vibepocket.control.MaxAgents
import au.edu.uts.vibepocket.control.Question
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Status
import au.edu.uts.vibepocket.control.Voice
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Binding
import au.edu.uts.vibepocket.profile.Choice
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.profile.Layer
import au.edu.uts.vibepocket.profile.Profile
import au.edu.uts.vibepocket.profile.Workflow
import org.json.JSONArray
import org.json.JSONObject

internal fun decode(root: JSONObject): Snapshot {
    val status = root.optJSONObject("status") ?: JSONObject()
    val controls = root.optJSONObject("controls") ?: JSONObject()
    return Snapshot(
        revision = root.safeString("revision") ?: "r_0",
        status = Status(
            state = status.safeString("state") ?: "degraded",
            message = status.safeString("message"),
        ),
        capabilities = Capabilities(
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
        desktop = decodeDesktop(root.optJSONObject("controller")),
    )
}

private fun decodeDesktop(value: JSONObject?): Desktop? {
    value ?: return null
    val profile = decodeProfile(value.optJSONObject("profile"))
    val agents = value.optJSONArray("agents").objects().mapNotNull { agent ->
        val id = agent.safeString("id")?.takeIf(AgentId::matches) ?: return@mapNotNull null
        val label = agent.safeString("label")?.take(64) ?: return@mapNotNull null
        Agent(
            id = id,
            label = label,
            activity = Activity.fromWire(agent.safeString("state").orEmpty()),
            focused = agent.optBoolean("focused", false),
        )
    }.take(MaxAgents)
    val focused = value.optInt("focusedAgentIndex", -1).takeIf { it in agents.indices } ?: -1
    val focusedAgentId = value.safeString("focusedAgentId")
        ?.takeIf(AgentId::matches)
        ?.takeIf { id -> agents.any { it.id == id } }
        ?: agents.singleOrNull { it.focused }?.id
    return Desktop(
        profile = profile,
        gestures = decodeGestures(value.optJSONArray("gestures")),
        choices = decodeChoices(value.optJSONArray("actionCatalog")),
        activeLayerId = value.safeString("activeLayerId")
            ?.takeIf { id -> profile?.layers?.any { it.id == id } == true },
        foreground = value.optBoolean("foreground", false),
        activity = Activity.fromWire(value.safeString("taskState").orEmpty()),
        agents = agents,
        focusedAgentIndex = focused,
        focusedAgentId = focusedAgentId,
        voice = decodeVoice(value.optJSONObject("voice")),
        mode = decodeSelector(value.optJSONObject("mode")),
        access = decodeSelector(value.optJSONObject("access")),
        reasoning = decodeReasoning(value.optJSONObject("reasoning")),
        question = decodeQuestion(value.optJSONObject("userInput")),
    )
}

private fun decodeQuestion(value: JSONObject?): Question? {
    value ?: return null
    val count = value.optInt("questionCount", 0).takeIf { it in 1..3 } ?: return null
    val index = value.optInt("questionIndex", -1).takeIf { it in 0 until count } ?: return null
    val header = value.safeString("header")?.take(64) ?: return null
    val question = value.safeString("question")?.take(2_000) ?: return null
    val options = value.optJSONArray("options").objects().mapNotNull { option ->
        val label = option.safeString("label")?.take(120) ?: return@mapNotNull null
        Question.Option(label, option.safeString("description")?.take(500).orEmpty())
    }.take(8)
    val selected = value.optInt("selectedOptionIndex", -1)
        .takeIf { it in options.indices }
        ?: -1
    return Question(
        index = index,
        count = count,
        header = header,
        text = question,
        options = options,
        selectedOptionIndex = selected,
        hasSpokenAnswer = value.optBoolean("hasSpokenAnswer", false),
        isSecret = value.optBoolean("isSecret", false),
    )
}

private fun decodeProfile(value: JSONObject?): Profile? {
    value ?: return null
    val inputs = value.optJSONArray("inputs").objects().mapNotNull { input ->
        val id = input.safeString("id")?.take(64) ?: return@mapNotNull null
        Input(
            id = id,
            kind = Input.Kind.fromWire(input.safeString("kind").orEmpty()),
            label = input.safeString("label")?.take(40) ?: id,
            icon = input.safeString("icon")?.take(24).orEmpty(),
        )
    }.distinctBy(Input::id)
    val workflows = value.optJSONArray("workflows").objects().mapNotNull { workflow ->
        val id = workflow.safeString("id")?.take(64) ?: return@mapNotNull null
        Workflow(
            id = id,
            label = workflow.safeString("label")?.take(40) ?: id,
            prompt = workflow.safeString("prompt")?.take(4_000).orEmpty(),
        )
    }.distinctBy(Workflow::id)
    val layers = value.optJSONArray("layers").objects().mapNotNull { layer ->
        val id = layer.safeString("id")?.take(64) ?: return@mapNotNull null
        val bindingsObject = layer.optJSONObject("bindings")
        val bindings = buildMap {
            bindingsObject?.keys()?.forEach { inputId ->
                val bindingObject = bindingsObject.optJSONObject(inputId) ?: return@forEach
                val actions = buildMap {
                    decodeAction(bindingObject)?.let { put(Gesture.Kind.TAP, it) }
                    Gesture.Kind.entries.forEach { gesture ->
                        decodeAction(bindingObject.optJSONObject(gesture.wireValue))?.let { put(gesture, it) }
                    }
                }
                if (actions.isNotEmpty()) put(inputId.take(64), Binding(actions))
            }
        }
        Layer(
            id = id,
            name = layer.safeString("name")?.take(40) ?: id,
            color = layer.safeString("color")?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) },
            bindings = bindings,
        )
    }.distinctBy(Layer::id).take(6)
    if (inputs.isEmpty() || layers.isEmpty()) return null
    return Profile(
        version = value.optInt("version", 1).coerceAtLeast(1),
        inputs = inputs,
        workflows = workflows,
        layers = layers,
    )
}

private fun decodeGestures(value: JSONArray?): List<Gesture> {
    val parsed = value.objects().mapNotNull { option ->
        val kind = Gesture.Kind.fromWire(option.safeString("id").orEmpty()) ?: return@mapNotNull null
        Gesture(kind, option.safeString("label")?.take(24) ?: kind.wireValue)
    }.distinctBy(Gesture::kind)
    return parsed.ifEmpty {
        listOf(
            Gesture(Gesture.Kind.TAP, "Tap"),
            Gesture(Gesture.Kind.DOUBLE_TAP, "Double tap"),
            Gesture(Gesture.Kind.HOLD, "Hold"),
        )
    }
}

private fun decodeChoices(value: JSONArray?): List<Choice> = value.objects().mapNotNull { entry ->
    val id = entry.safeString("id")?.take(64) ?: return@mapNotNull null
    val action = decodeAction(entry.optJSONObject("action")) ?: return@mapNotNull null
    Choice(id, entry.safeString("label")?.take(48) ?: id, action)
}.distinctBy(Choice::id).take(64)

private fun decodeAction(value: JSONObject?): Action? {
    value ?: return null
    val type = value.safeString("type") ?: return null
    return when (type) {
        "approve", "reject", "voice", "new_task", "stop", "mode_cycle", "access_cycle", "clear_input", "focus_next", "attach" ->
            Action(type)
        "navigate" -> value.safeString("direction")
            ?.takeIf { it in setOf("up", "down", "left", "right") }
            ?.let { Action(type, direction = it) }
        "reasoning_depth" -> value.optInt("delta", 0)
            .takeIf { it == -1 || it == 1 }
            ?.let { Action(type, delta = it) }
        "focus_agent" -> value.optInt("index", -1)
            .takeIf { it in 0..5 }
            ?.let { Action(type, index = it) }
        "select_layer" -> value.safeString("layerId")
            ?.take(64)
            ?.let { Action(type, layerId = it) }
        "workflow" -> value.safeString("workflowId")
            ?.take(64)
            ?.let { Action(type, workflowId = it) }
        else -> null
    }
}

private fun decodeSelector(value: JSONObject?): Selector = Selector(
    available = value?.optBoolean("available", false) == true,
    label = value?.safeString("label")?.take(64).orEmpty(),
)

private fun decodeReasoning(value: JSONObject?): Reasoning {
    val available = value?.optBoolean("available", false) == true
    return Reasoning(
        available = available,
        label = value?.safeString("label")?.take(64).orEmpty(),
        modelLabel = value?.safeString("modelLabel")?.take(64).orEmpty(),
        level = Reasoning.Level.fromWire(value?.safeString("level")),
        // These fields were added after protocol v5. Defaulting to the overall
        // capability keeps a rolling bridge/app upgrade interactive.
        canIncrease = available && value.optBoolean("canIncrease", true),
        canDecrease = available && value.optBoolean("canDecrease", true),
    )
}

private fun decodeVoice(value: JSONObject?): Voice? = value?.let {
    Voice(
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

internal fun Action.encode(): JSONObject = JSONObject().put("type", type).also { root ->
    direction?.let { root.put("direction", it) }
    delta?.let { root.put("delta", it) }
    index?.let { root.put("index", it) }
    workflowId?.let { root.put("workflowId", it) }
    layerId?.let { root.put("layerId", it) }
}

internal fun Command.encode(): JSONObject = when (this) {
    is Command.Binding -> JSONObject()
        .put("kind", "binding")
        .put("inputId", inputId)
        .put("gesture", gesture.wireValue)
    is Command.SelectLayer -> JSONObject().put("kind", "select_layer").put("layerId", layerId)
    is Command.FocusAgent -> JSONObject().put("kind", "focus_agent").put("agentId", agentId)
    is Command.UpdateBinding -> JSONObject()
        .put("kind", "update_binding")
        .put("layerId", layerId)
        .put("inputId", inputId)
        .put("gesture", gesture.wireValue)
        .put("action", action.encode())
    is Command.ClearBinding -> JSONObject()
        .put("kind", "clear_binding")
        .put("layerId", layerId)
        .put("inputId", inputId)
        .put("gesture", gesture.wireValue)
    is Command.RenameLayer -> JSONObject()
        .put("kind", "rename_layer")
        .put("layerId", layerId)
        .put("name", name)
    is Command.UpdateLayerColor -> JSONObject()
        .put("kind", "update_layer_color")
        .put("layerId", layerId)
        .put("color", color)
    is Command.UpdateWorkflowPrompt -> JSONObject()
        .put("kind", "update_workflow")
        .put("workflowId", workflowId)
        .put("prompt", prompt)
    Command.ResetProfile -> JSONObject().put("kind", "reset_profile")
    Command.Attach -> JSONObject().put("kind", "attach")
    Command.Voice -> JSONObject().put("kind", "voice")
    Command.VoiceStart -> JSONObject().put("kind", "voice_start")
    Command.VoiceStop -> JSONObject().put("kind", "voice_stop")
    Command.Stop -> JSONObject().put("kind", "stop")
    Command.NewTask -> JSONObject().put("kind", "new_task")
    Command.Approve -> JSONObject().put("kind", "approve")
    Command.Reject -> JSONObject().put("kind", "reject")
}
