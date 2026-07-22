package au.edu.uts.vibepocket.bridge

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Agent
import au.edu.uts.vibepocket.control.AgentId
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.Desktop
import au.edu.uts.vibepocket.control.MaxAgents
import au.edu.uts.vibepocket.control.Model
import au.edu.uts.vibepocket.control.Question
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Sources
import au.edu.uts.vibepocket.control.Status
import au.edu.uts.vibepocket.control.TargetRef
import au.edu.uts.vibepocket.control.Tasks
import au.edu.uts.vibepocket.control.Voice
import au.edu.uts.vibepocket.control.decodeAction
import au.edu.uts.vibepocket.control.encode
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
    if (root.opt("protocolVersion") != ProtocolVersion) {
        throw Failure("The Vibe Pocket bridge returned an incompatible snapshot protocol version.")
    }
    val status = root.optJSONObject("status") ?: JSONObject()
    val controls = root.optJSONObject("controls") ?: JSONObject()
    val observation = decodeObservation(root.optJSONObject("observation"))
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
            modelPicker = controls.optBoolean("model-picker", false),
            model = controls.optBoolean("model", false),
            accessCycle = controls.optBoolean("access-cycle", false),
            navigate = controls.optBoolean("navigate", false),
            reasoning = controls.optBoolean("reasoning", false),
            workflow = controls.optBoolean("workflow", false),
        ),
        desktop = decodeDesktop(root.optJSONObject("controller")),
        observedAtMillis = observation.observedAtMillis,
        transportFresh = observation.fresh,
        sources = decodeSources(root.optJSONObject("sources")),
    )
}

private data class Observation(
    val fresh: Boolean,
    val observedAtMillis: Long?,
)

private fun decodeObservation(value: JSONObject?): Observation {
    value ?: return Observation(fresh = false, observedAtMillis = null)
    val raw = value.opt("observedAt") as? Number
    val observedAt = raw?.toLong()?.takeIf {
        it > 0L && raw.toDouble().isFinite() && raw.toDouble() == it.toDouble()
    }
    return Observation(
        fresh = value.opt("fresh") == true && observedAt != null,
        observedAtMillis = observedAt,
    )
}

private fun decodeSources(value: JSONObject?): Sources = Sources(
    appServer = decodeSource(value?.optJSONObject("appServer")),
    desktopUI = decodeSource(value?.optJSONObject("desktopUI")),
)

private fun decodeSource(value: JSONObject?): Sources.Source {
    value ?: return Sources.Source(false)
    val raw = value.opt("observedAt") as? Number
    val observedAt = raw?.toLong()?.takeIf {
        it > 0L && raw.toDouble().isFinite() && raw.toDouble() == it.toDouble()
    }
    return Sources.Source(
        fresh = value.opt("fresh") == true,
        observedAtMillis = observedAt,
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
            freshness = if (agent.safeString("freshness") == "fresh") {
                Agent.Freshness.FRESH
            } else {
                Agent.Freshness.STALE
            },
            actionable = agent.opt("actionable") == true,
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
        model = decodeModel(value.optJSONObject("model")),
        reasoning = decodeReasoning(value.optJSONObject("reasoning")),
        question = decodeQuestion(value.optJSONObject("userInput")),
        tasks = decodeTasks(value.optJSONObject("tasks")),
        binding = decodeBinding(value.optJSONObject("binding"), focusedAgentId),
    )
}

private fun decodeBinding(value: JSONObject?, focusedAgentId: String?): Desktop.Binding {
    val visible = value?.optJSONObject("visible")
    val visibleState = visible?.safeString("state") ?: value?.safeString("state")
    val contextId = (visible?.safeString("contextId") ?: visible?.safeString("agentId")
        ?: value?.safeString("contextId"))?.takeIf(AgentId::matches)
    val state = when (visibleState) {
        "confirmed" -> if (contextId != null && contextId == focusedAgentId) {
            Desktop.Binding.State.CONFIRMED
        } else {
            Desktop.Binding.State.CONFLICT
        }
        "reconciling" -> Desktop.Binding.State.RECONCILING
        "conflict" -> Desktop.Binding.State.CONFLICT
        "unbound" -> Desktop.Binding.State.UNBOUND
        else -> if (focusedAgentId == null) {
            Desktop.Binding.State.UNBOUND
        } else {
            Desktop.Binding.State.RECONCILING
        }
    }
    return Desktop.Binding(
        state = state,
        contextId = contextId.takeUnless { state == Desktop.Binding.State.UNBOUND },
        target = decodeBindingTarget(value?.optJSONObject("target")),
    )
}

private fun decodeBindingTarget(value: JSONObject?): Desktop.Binding.Target {
    value ?: return Desktop.Binding.Target.Unbound
    return when (value.safeString("state")) {
        "bound" -> runCatching {
            val encoded = value.optJSONObject("ref") ?: value
            Desktop.Binding.Target.bound(decodeTargetRef(encoded))
        }.getOrElse { Desktop.Binding.Target.Invalid }
        "invalid" -> Desktop.Binding.Target.Invalid
        "unbound" -> Desktop.Binding.Target.Unbound
        else -> Desktop.Binding.Target.Invalid
    }
}

internal fun decodeTargetRef(value: JSONObject): TargetRef = TargetRef(
    threadId = value.requiredTargetString("threadId", 512),
    agentId = value.requiredTargetString("agentId", 64),
    bindingEpoch = value.requiredTargetLong("bindingEpoch"),
    bridgeInstanceId = value.requiredTargetString("bridgeInstanceId", 256),
    appServerGeneration = value.requiredTargetLong("appServerGeneration"),
    canonicalWorkspaceId = value.requiredTargetString("canonicalWorkspaceId", 2_048),
)

private fun JSONObject.requiredTargetString(key: String, limit: Int): String =
    requireNotNull(safeString(key)?.takeIf { it.length <= limit }) {
        "The target reference is missing $key."
    }

private fun JSONObject.requiredTargetLong(key: String): Long {
    val raw = opt(key) as? Number
        ?: throw IllegalArgumentException("The target reference is missing $key.")
    val value = raw.toLong()
    require(raw.toDouble().isFinite() && raw.toDouble() == value.toDouble()) {
        "The target reference $key is invalid."
    }
    return value
}

private fun decodeTasks(value: JSONObject?): Tasks {
    value ?: return Tasks.Unavailable
    val availability = when (value.safeString("availability")) {
        "fresh" -> Tasks.Availability.FRESH
        "stale" -> Tasks.Availability.STALE
        else -> Tasks.Availability.UNAVAILABLE
    }
    return Tasks(availability, value.safeString("message")?.take(500))
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

private fun decodeSelector(value: JSONObject?): Selector = Selector(
    available = value?.optBoolean("available", false) == true,
    label = value?.safeString("label")?.take(64).orEmpty(),
    id = value?.safeString("id")?.takeIf { it.matches(Regex("^[a-zA-Z0-9._-]{1,64}$")) },
    options = value?.optJSONArray("options").objects().mapNotNull { option ->
        val id = option.safeString("id")
            ?.takeIf { it.matches(Regex("^[a-zA-Z0-9._-]{1,64}$")) }
            ?: return@mapNotNull null
        Selector.Option(
            id = id,
            label = option.safeString("label")?.take(64) ?: id,
            selected = option.optBoolean("selected", false),
        )
    }.distinctBy(Selector.Option::id).take(8),
)

private fun decodeReasoning(value: JSONObject?): Reasoning {
    val available = value?.optBoolean("available", false) == true
    return Reasoning(
        available = available,
        label = value?.safeString("label")?.take(64).orEmpty(),
        level = Reasoning.Level.fromWire(value?.safeString("level")),
        // These fields were added after protocol v5. Defaulting to the overall
        // capability keeps a rolling bridge/app upgrade interactive.
        canIncrease = available && value.optBoolean("canIncrease", true),
        canDecrease = available && value.optBoolean("canDecrease", true),
        increaseTo = Reasoning.Level.fromWire(value?.safeString("increaseTo")),
        decreaseTo = Reasoning.Level.fromWire(value?.safeString("decreaseTo")),
        options = value?.optJSONArray("options").strings()
            .mapNotNull(Reasoning.Level::fromWire)
            .distinct(),
    )
}

private fun decodeModel(value: JSONObject?): Model {
    value ?: return Model.Unavailable
    val options = value.optJSONArray("options").objects().mapNotNull { option ->
        val id = option.safeString("id")
            ?.takeIf { it.matches(Regex("^[a-zA-Z0-9._-]{1,128}$")) }
            ?: return@mapNotNull null
        Model.Option(
            id = id,
            label = option.safeString("label")?.take(80) ?: id,
            selected = option.optBoolean("selected", false),
        )
    }.distinctBy(Model.Option::id).take(20)
    val selectedId = value.safeString("id")?.takeIf { id -> options.any { it.id == id } }
        ?: options.singleOrNull { it.selected }?.id
    return Model(
        available = value.optBoolean("available", false) && options.isNotEmpty(),
        id = selectedId,
        label = value.safeString("label")?.take(80).orEmpty(),
        options = options.map { it.copy(selected = it.id == selectedId) },
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

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) optString(index, "").takeIf(String::isNotBlank)?.let(::add)
    }
}

private fun JSONObject.safeString(key: String): String? =
    optString(key, "").trim().takeUnless { it.isEmpty() || it == "null" }
