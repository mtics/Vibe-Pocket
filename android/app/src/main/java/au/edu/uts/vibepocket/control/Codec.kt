package au.edu.uts.vibepocket.control

import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Gesture
import org.json.JSONObject

internal fun Action.encode(): JSONObject = JSONObject().put("type", type).also { root ->
    direction?.let { root.put("direction", it) }
    delta?.let { root.put("delta", it) }
    index?.let { root.put("index", it) }
    workflowId?.let { root.put("workflowId", it) }
    layerId?.let { root.put("layerId", it) }
}

internal fun decodeAction(value: JSONObject?): Action? {
    value ?: return null
    val type = value.text("type") ?: return null
    return when (type) {
        "approve", "reject", "voice", "new_task", "stop", "mode_cycle", "model_picker", "access_cycle",
        "delete_backward", "clear_input", "focus_next", "attach" -> Action(type)
        "navigate" -> value.text("direction")
            ?.takeIf { it in setOf("up", "down", "left", "right") }
            ?.let { Action(type, direction = it) }
        "reasoning_depth" -> value.optInt("delta", 0)
            .takeIf { it == -1 || it == 1 }
            ?.let { Action(type, delta = it) }
        "focus_agent" -> value.optInt("index", -1)
            .takeIf { it in 0..5 }
            ?.let { Action(type, index = it) }
        "select_layer" -> value.text("layerId")
            ?.take(64)
            ?.let { Action(type, layerId = it) }
        "workflow" -> value.text("workflowId")
            ?.take(64)
            ?.let { Action(type, workflowId = it) }
        else -> null
    }
}

internal fun Command.encode(): JSONObject = when (this) {
    is Command.Binding -> JSONObject()
        .put("kind", "binding")
        .put("inputId", inputId)
        .put("gesture", gesture.wireValue)
        .put("layerId", layerId)
        .put("action", action.encode())
    is Command.SelectLayer -> JSONObject().put("kind", "select_layer").put("layerId", layerId)
    is Command.FocusAgent -> JSONObject().put("kind", "focus_agent").put("agentId", agentId)
    is Command.SelectModel -> JSONObject().put("kind", "select_model").put("modelId", modelId)
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
    Command.ModelPicker -> JSONObject().put("kind", "model_picker")
    Command.Approve -> JSONObject().put("kind", "approve")
    Command.Reject -> JSONObject().put("kind", "reject")
}

internal fun decodeCommand(value: JSONObject): Command {
    fun field(name: String, limit: Int = 512): String = requireNotNull(value.text(name)?.take(limit)) {
        "The pending command is missing $name."
    }
    fun gesture(): Gesture.Kind = requireNotNull(Gesture.Kind.fromWire(field("gesture", 32))) {
        "The pending command gesture is invalid."
    }
    fun action(): Action = decodeCommandAction(
        value.optJSONObject("action")
            ?: error("The pending command action is invalid."),
    )

    return when (field("kind", 64)) {
        "binding" -> Command.Binding(field("inputId"), gesture(), field("layerId"), action())
        "select_layer" -> Command.SelectLayer(field("layerId"))
        "focus_agent" -> Command.FocusAgent(field("agentId"))
        "select_model" -> Command.SelectModel(field("modelId", 128))
        "update_binding" -> Command.UpdateBinding(field("layerId"), field("inputId"), gesture(), action())
        "clear_binding" -> Command.ClearBinding(field("layerId"), field("inputId"), gesture())
        "rename_layer" -> Command.RenameLayer(field("layerId"), field("name"))
        "update_layer_color" -> Command.UpdateLayerColor(field("layerId"), field("color", 64))
        "update_workflow" -> Command.UpdateWorkflowPrompt(field("workflowId"), field("prompt", 8_000))
        "reset_profile" -> Command.ResetProfile
        "attach" -> Command.Attach
        "voice" -> Command.Voice
        "voice_start" -> Command.VoiceStart
        "voice_stop" -> Command.VoiceStop
        "stop" -> Command.Stop
        "new_task" -> Command.NewTask
        "model_picker" -> Command.ModelPicker
        "approve" -> Command.Approve
        "reject" -> Command.Reject
        else -> error("The pending command kind is invalid.")
    }
}

private fun decodeCommandAction(value: JSONObject): Action = Action(
    type = requireNotNull(value.text("type")) { "The pending command action type is invalid." },
    direction = value.optionalText("direction"),
    delta = value.optionalInt("delta"),
    index = value.optionalInt("index"),
    workflowId = value.optionalText("workflowId"),
    layerId = value.optionalText("layerId"),
)

private fun JSONObject.text(key: String): String? =
    optString(key, "").trim().takeUnless { it.isEmpty() || it == "null" }

private fun JSONObject.optionalText(key: String): String? =
    takeIf { has(key) && !isNull(key) }?.getString(key)

private fun JSONObject.optionalInt(key: String): Int? =
    takeIf { has(key) && !isNull(key) }?.getInt(key)
