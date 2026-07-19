package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.inputAllowsQueuedRepeat
import au.edu.uts.vibepocket.gesture.layer.isTarget
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun InputButton(
    input: Input,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    navigationRepeatEnabled: Boolean = false,
    onNavigationRepeat: (String, Boolean) -> Unit = { _, _ -> },
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    shift: Boolean = false,
    blocked: Boolean = false,
    onLayerChord: (String) -> Boolean = { false },
    iconOnly: Boolean = false,
    shape: Shape = RoundedCornerShape(8.dp),
    modifier: Modifier,
) {
    val gestures = Gesture.Kind.entries.filter { snapshot.inputEnabled(input.id, it) }
    val action = snapshot.actionFor(input.id)
    val label = snapshot.desktop?.choices?.firstOrNull { it.action == action }?.label ?: input.label
    val icon = icon(action, input)
    val accent = accent(action)
    val voice = snapshot.voiceTapEnabled(input.id) && !shift
    val pending = inFlightIds.any { it.startsWith("input:${input.id}:") } &&
        !snapshot.inputAllowsQueuedRepeat(input.id)
    val interactive = !blocked && (voice || gestures.isNotEmpty())
    val voiceEnabled = voice && !pending && !blocked
    val repeat = navigationRepeatEnabled && !pending && !blocked && !shift
    val chord = shift && !blocked && isTarget(input.id)
    val currentVoiceStart by rememberUpdatedState(onVoiceStart)
    val currentVoiceStop by rememberUpdatedState(onVoiceStop)
    val currentRepeat by rememberUpdatedState(onNavigationRepeat)
    val currentChord by rememberUpdatedState(onLayerChord)
    val mapped = gestures.joinToString { it.shortLabel }

    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, accent.copy(alpha = if (interactive) 0.42f else 0.16f), shape)
            .semantics {
                role = Role.Button
                contentDescription = if (mapped.isEmpty()) label else "$label. Gestures $mapped"
            }
            .pointerInput(input.id, chord) {
                if (!chord) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentChord(input.id)) return@awaitEachGesture
                    down.consume()
                    var held = true
                    while (held) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        held = change?.pressed == true
                        change?.consume()
                    }
                }
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
            .alpha(if (interactive) 1f else 0.36f)
            .padding(horizontal = if (iconOnly) 0.dp else 6.dp, vertical = if (iconOnly) 0.dp else 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (pending) {
            CircularProgressIndicator(Modifier.size(if (iconOnly) 22.dp else 20.dp), color = accent, strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(if (iconOnly) 27.dp else 23.dp))
            if (!iconOnly) {
                Spacer(Modifier.height(5.dp))
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun accent(action: Action?): Color = when (action?.type) {
    "approve" -> MaterialTheme.colorScheme.primary
    "reject", "stop" -> ErrorColor
    "voice" -> MaterialTheme.colorScheme.tertiary
    "new_task" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun icon(action: Action?, input: Input): ImageVector = when (action?.type) {
    "approve" -> iconForInput("check")
    "reject" -> iconForInput("close")
    "voice" -> iconForInput("mic")
    "new_task" -> iconForInput("add")
    "stop" -> iconForInput("stop")
    "mode_cycle", "access_cycle", "select_layer" -> iconForInput("cycle")
    "clear_input" -> iconForInput("clear")
    "focus_next", "focus_agent" -> iconForInput("agent")
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
