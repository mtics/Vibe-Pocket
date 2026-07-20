package au.edu.uts.vibepocket.control

import au.edu.uts.vibepocket.profile.Action

enum class ConflictGroup {
    DECISION,
    CONTEXT,
    DRAFT,
    VOICE,
    RUN,
    NAVIGATION,
}

internal fun Command.conflictGroups(): Set<ConflictGroup> = when (this) {
    is Command.Binding -> action.conflictGroups()
    is Command.FocusAgent,
    is Command.SelectLayer,
    is Command.SelectModel,
    is Command.SelectMode,
    is Command.SelectReasoning,
    Command.Attach,
    Command.ModelPicker,
    -> setOf(ConflictGroup.CONTEXT)
    Command.NewTask -> setOf(ConflictGroup.CONTEXT, ConflictGroup.RUN)
    Command.Approve, Command.Reject -> setOf(ConflictGroup.DECISION)
    Command.Stop -> setOf(ConflictGroup.RUN)
    Command.Voice, Command.VoiceStart, Command.VoiceStop -> setOf(ConflictGroup.VOICE)
    is Command.ClearBinding,
    is Command.RenameLayer,
    is Command.ResetProfile,
    is Command.UpdateBinding,
    is Command.UpdateLayerColor,
    is Command.UpdateWorkflowPrompt,
    -> emptySet()
}

internal fun Action.conflictGroups(): Set<ConflictGroup> = when (type) {
    "approve", "reject" -> setOf(ConflictGroup.DECISION)
    "access_cycle", "attach", "focus_agent", "focus_next", "mode_cycle", "model_picker",
    "reasoning_depth", "select_layer",
    -> setOf(ConflictGroup.CONTEXT)
    "new_task" -> setOf(ConflictGroup.CONTEXT, ConflictGroup.RUN)
    "workflow" -> setOf(ConflictGroup.DRAFT)
    "clear_input", "delete_backward" -> setOf(ConflictGroup.DRAFT)
    "stop" -> setOf(ConflictGroup.RUN)
    "voice" -> setOf(ConflictGroup.VOICE)
    "navigate" -> setOf(ConflictGroup.NAVIGATION)
    else -> emptySet()
}
