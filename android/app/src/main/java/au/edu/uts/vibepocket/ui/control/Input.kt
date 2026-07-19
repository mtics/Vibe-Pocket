package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.inputAllowsQueuedRepeat
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.ui.ErrorColor
import au.edu.uts.vibepocket.ui.iconForInput
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class LabelPlacement {
    HIDDEN,
    BELOW,
    BESIDE,
    TEXT,
}

@Composable
internal fun InputButton(
    input: Input,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    navigationRepeatEnabled: Boolean = false,
    onNavigationRepeat: (String, Boolean) -> Unit = { _, _ -> },
    onVoiceStart: (String) -> Boolean = { false },
    onVoiceStop: (String) -> Unit = {},
    blocked: Boolean = false,
    labelPlacement: LabelPlacement = LabelPlacement.BELOW,
    labelOverride: String? = null,
    supportingLabel: String? = null,
    shape: Shape = RoundedCornerShape(8.dp),
    modifier: Modifier,
) {
    val gestures = Gesture.Kind.entries.filter { snapshot.inputEnabled(input.id, it) }
    val action = snapshot.actionFor(input.id)
    val voice = snapshot.voiceTapEnabled(input.id)
    val voiceActive = voice && snapshot.desktop?.voice?.active == true
    val label = if (voiceActive) "Listening..." else labelOverride ?: label(action, input, snapshot)
    val icon = icon(action, input)
    val accent = accent(action, voiceActive)
    val container = container(action, voiceActive)
    val pending = inFlightIds.any { it.startsWith("input:${input.id}:") } &&
        !snapshot.inputAllowsQueuedRepeat(input.id)
    val interactive = !blocked && (voice || gestures.isNotEmpty())
    val voiceEnabled = voice && !pending && !blocked
    val repeat = navigationRepeatEnabled && !pending && !blocked
    val currentVoiceStart by rememberUpdatedState(onVoiceStart)
    val currentVoiceStop by rememberUpdatedState(onVoiceStop)
    val currentRepeat by rememberUpdatedState(onNavigationRepeat)
    val mapped = gestures.joinToString { kind ->
        snapshot.desktop?.gestures?.firstOrNull { it.kind == kind }?.label
            ?: kind.wireValue.replace('_', ' ')
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(container)
            .border(1.dp, accent.copy(alpha = if (interactive) 0.42f else 0.16f), shape)
            .semantics {
                role = Role.Button
                val description = supportingLabel?.let { "$it, $label" } ?: label
                contentDescription = if (mapped.isEmpty()) description else "$description. Gestures $mapped"
                if (voice) stateDescription = if (voiceActive) "Listening" else "Ready to listen"
            }
            .pointerInput(input.id, voiceEnabled) {
                if (!voiceEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val started = currentVoiceStart(input.id)
                    try {
                        var held = true
                        while (held) {
                            held = awaitPointerEvent().changes.firstOrNull { it.id == down.id }?.pressed == true
                        }
                    } finally {
                        if (started) currentVoiceStop(input.id)
                    }
                }
            }
            .pointerInput(input.id, repeat) {
                if (!repeat) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    currentRepeat(input.id, true)
                    try {
                        var held = true
                        while (held) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            held = change?.pressed == true
                            change?.consume()
                        }
                    } finally {
                        currentRepeat(input.id, false)
                    }
                }
            }
            .combinedClickable(
                enabled = !voice && gestures.isNotEmpty() && !pending && !blocked && !repeat,
                onClick = { if (Gesture.Kind.TAP in gestures) onInput(input.id, Gesture.Kind.TAP) },
                onDoubleClick = if (Gesture.Kind.DOUBLE_TAP in gestures) {
                    { onInput(input.id, Gesture.Kind.DOUBLE_TAP) }
                } else null,
                onLongClick = if (Gesture.Kind.HOLD in gestures) {
                    { onInput(input.id, Gesture.Kind.HOLD) }
                } else null,
                onLongClickLabel = if (Gesture.Kind.HOLD in gestures) "Run hold binding" else null,
            )
            .alpha(if (interactive) 1f else 0.62f)
            .padding(
                horizontal = when (labelPlacement) {
                    LabelPlacement.HIDDEN -> 0.dp
                    LabelPlacement.TEXT -> 4.dp
                    else -> 8.dp
                },
                vertical = if (labelPlacement == LabelPlacement.BELOW) 7.dp else 0.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (pending) {
            CircularProgressIndicator(
                Modifier.size(if (labelPlacement == LabelPlacement.HIDDEN) 22.dp else 20.dp),
                color = accent,
                strokeWidth = 2.dp,
            )
        } else {
            when (labelPlacement) {
                LabelPlacement.HIDDEN -> {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(27.dp))
                }
                LabelPlacement.BELOW -> {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(23.dp))
                    Spacer(Modifier.height(5.dp))
                    Label(label)
                }
                LabelPlacement.BESIDE -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(7.dp))
                        Label(label)
                    }
                }
                LabelPlacement.TEXT -> if (supportingLabel == null) {
                    Label(label, compact = true)
                } else {
                    Text(
                        supportingLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Label(label, compact = true)
                }
            }
        }
    }
}

@Composable
private fun Label(value: String, compact: Boolean = false) {
    Text(
        value,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
    )
}

private fun label(action: Action?, input: Input, snapshot: Snapshot): String = when (action?.type) {
    "approve" -> "Accept"
    "reject" -> "Reject"
    "voice" -> "Voice"
    "clear_input" -> "Clear"
    "new_task" -> "New task"
    "stop" -> "Stop"
    "mode_cycle" -> "Mode"
    "model_picker" -> "Model"
    "access_cycle" -> "Access"
    "delete_backward" -> "Delete"
    "focus_next" -> "Next agent"
    "attach" -> "Focus Codex"
    else -> snapshot.desktop?.choices?.firstOrNull { it.action == action }?.label ?: input.label
}

@Composable
private fun accent(action: Action?, active: Boolean): Color = when {
    active -> MaterialTheme.colorScheme.tertiary
    else -> when (action?.type) {
        "approve" -> MaterialTheme.colorScheme.primary
        "reject", "stop" -> ErrorColor
        "voice" -> MaterialTheme.colorScheme.tertiary
        "new_task" -> MaterialTheme.colorScheme.secondary
        "workflow" -> when (action.workflowId) {
            "review-pr" -> MaterialTheme.colorScheme.primary
            "debug" -> ErrorColor
            "refactor" -> MaterialTheme.colorScheme.secondary
            "test" -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun container(action: Action?, active: Boolean): Color = when {
    active -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.24f)
    else -> when (action?.type) {
        "approve" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)
        "reject", "stop" -> ErrorColor.copy(alpha = 0.08f)
        "voice" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.11f)
        "new_task" -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.09f)
        "workflow" -> when (action.workflowId) {
            "review-pr" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            "debug" -> ErrorColor.copy(alpha = 0.08f)
            "refactor" -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.09f)
            "test" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.09f)
            else -> MaterialTheme.colorScheme.surface
        }
        else -> MaterialTheme.colorScheme.surface
    }
}

private fun icon(action: Action?, input: Input): ImageVector = when (action?.type) {
    "approve" -> iconForInput("check")
    "reject" -> iconForInput("close")
    "voice" -> iconForInput("mic")
    "new_task" -> iconForInput("add")
    "stop" -> iconForInput("stop")
    "mode_cycle" -> Icons.Default.SwapHoriz
    "model_picker" -> Icons.Default.SmartToy
    "access_cycle" -> Icons.Default.AdminPanelSettings
    "select_layer" -> Icons.Default.Layers
    "delete_backward", "clear_input" -> iconForInput("clear")
    "focus_next" -> Icons.Default.SwitchAccount
    "focus_agent" -> Icons.Default.AccountCircle
    "attach" -> iconForInput("focus")
    "navigate" -> when (action.direction) {
        "up" -> Icons.Default.ArrowUpward
        "down" -> Icons.Default.ArrowDownward
        "left" -> Icons.AutoMirrored.Filled.ArrowBack
        else -> Icons.AutoMirrored.Filled.ArrowForward
    }
    "reasoning_depth" -> if (action.delta == -1) Icons.Default.Remove else Icons.Default.Add
    else -> iconForInput(input.icon)
}
