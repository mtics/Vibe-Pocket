package au.edu.uts.vibepocket.ui.control.state

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Tasks
import au.edu.uts.vibepocket.session.Operation

internal data class State(
    val kind: Kind,
    val activity: Activity,
    val title: String,
    val task: String?,
    val detail: String?,
    val meta: String? = null,
    val selection: String? = null,
) {
    enum class Kind {
        READY,
        RUNNING,
        QUESTION,
        DECISION,
        STALE,
        UNKNOWN,
        ERROR,
    }
}

internal fun Snapshot.state(operation: Operation? = null): State {
    val desktop = desktop
    if (!transportFresh) {
        return State(
            kind = State.Kind.STALE,
            activity = desktop?.activity ?: Activity.ERROR,
            title = "Connection stale",
            task = desktop?.agents?.firstOrNull { it.id == desktop.focusedAgentId || it.focused }?.label,
            detail = "Showing the last confirmed Codex state.",
        )
    }
    if (status.state != "ready" || desktop == null) {
        return State(
            kind = State.Kind.ERROR,
            activity = Activity.ERROR,
            title = "Bridge unavailable",
            task = null,
            detail = status.message?.takeIf(String::isNotBlank),
        )
    }

    when (operation?.phase) {
        Operation.Phase.UNKNOWN -> return State(
            kind = State.Kind.UNKNOWN,
            activity = Activity.WAITING,
            title = "Outcome unknown",
            task = focusedTaskLabel(),
            detail = operation.message
                ?: "The Mac may have completed this command. The last confirmed state is unchanged.",
        )
        Operation.Phase.FAILED -> return State(
            kind = State.Kind.ERROR,
            activity = Activity.ERROR,
            title = "Command failed",
            task = focusedTaskLabel(),
            detail = operation.message,
        )
        else -> Unit
    }

    if (desktop.tasks.availability != Tasks.Availability.FRESH) {
        return State(
            kind = State.Kind.STALE,
            activity = desktop.activity,
            title = if (desktop.tasks.availability == Tasks.Availability.STALE) {
                "Task list stale"
            } else {
                "Task list unavailable"
            },
            task = desktop.agents.firstOrNull()?.label,
            detail = desktop.tasks.message ?: "Showing the last confirmed Codex tasks.",
        )
    }

    val task = desktop.agents
        .firstOrNull { it.id == desktop.focusedAgentId || it.focused }
        ?.label
        ?.takeIf(String::isNotBlank)

    desktop.question?.let { question ->
        val option = question.options.getOrNull(question.selectedOptionIndex)
        return State(
            kind = State.Kind.QUESTION,
            activity = Activity.WAITING,
            title = question.header.ifBlank { "Codex needs input" },
            task = task,
            detail = question.text.takeIf(String::isNotBlank),
            meta = "Question ${question.index + 1} of ${question.count}",
            selection = option?.let { selected ->
                listOf(selected.label, selected.description.takeIf(String::isNotBlank))
                    .filterNotNull()
                    .joinToString("  ")
            },
        )
    }

    val message = status.message?.takeIf(String::isNotBlank)
    return when (desktop.activity) {
        Activity.THINKING, Activity.EXECUTING -> State(
            kind = State.Kind.RUNNING,
            activity = desktop.activity,
            title = if (desktop.activity == Activity.THINKING) "Codex is thinking" else "Codex is working",
            task = task,
            detail = message,
        )
        Activity.WAITING -> State(
            kind = State.Kind.DECISION,
            activity = desktop.activity,
            title = "Codex needs your decision",
            task = task,
            detail = message,
        )
        Activity.ERROR -> State(
            kind = State.Kind.ERROR,
            activity = desktop.activity,
            title = "Task unavailable",
            task = task,
            detail = message,
        )
        Activity.COMPLETE -> State(
            kind = State.Kind.READY,
            activity = desktop.activity,
            title = "Task complete",
            task = task,
            detail = message,
        )
        Activity.UNREAD -> State(
            kind = State.Kind.READY,
            activity = desktop.activity,
            title = "Result ready",
            task = task,
            detail = message,
        )
        Activity.IDLE -> State(
            kind = State.Kind.READY,
            activity = desktop.activity,
            title = "Ready",
            task = task,
            detail = message,
        )
    }
}

private fun Snapshot.focusedTaskLabel(): String? = desktop?.agents
    ?.firstOrNull { it.id == desktop.focusedAgentId || it.focused }
    ?.label
    ?.takeIf(String::isNotBlank)
