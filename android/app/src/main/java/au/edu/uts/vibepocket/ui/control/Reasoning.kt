package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Reasoning as State
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.inputAllowsQueuedRepeat
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun Reasoning(
    inputs: List<Input>,
    state: State,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val decrease = reasoningInput(inputs, snapshot, delta = -1)
    val increase = reasoningInput(inputs, snapshot, delta = 1)

    Row(
        modifier = modifier.heightIn(min = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Step(decrease, Icons.Default.Remove, "Decrease reasoning", snapshot, inFlightIds, onInput, blocked)
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Reasoning",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                state.level?.displayLabel ?: state.label.ifBlank { "Unavailable" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Step(increase, Icons.Default.Add, "Increase reasoning", snapshot, inFlightIds, onInput, blocked)
    }
}

@Composable
private fun Step(
    input: Input?,
    icon: ImageVector,
    contentDescription: String,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    blocked: Boolean,
) {
    if (input == null) {
        Spacer(Modifier.size(42.dp))
        return
    }
    val gestures = Gesture.Kind.entries.filter { snapshot.inputEnabled(input.id, it) }
    val pending = inFlightIds.any { it.startsWith("input:${input.id}:") } &&
        !snapshot.inputAllowsQueuedRepeat(input.id)
    val enabled = !blocked && gestures.isNotEmpty() && !pending
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .combinedClickable(
                enabled = enabled,
                onClick = { if (Gesture.Kind.TAP in gestures) onInput(input.id, Gesture.Kind.TAP) },
                onDoubleClick = if (Gesture.Kind.DOUBLE_TAP in gestures) {
                    { onInput(input.id, Gesture.Kind.DOUBLE_TAP) }
                } else null,
                onLongClick = if (Gesture.Kind.HOLD in gestures) {
                    { onInput(input.id, Gesture.Kind.HOLD) }
                } else null,
            )
            .alpha(if (gestures.isNotEmpty()) 1f else 0.62f),
        contentAlignment = Alignment.Center,
    ) {
        if (pending) {
            CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
    }
}
