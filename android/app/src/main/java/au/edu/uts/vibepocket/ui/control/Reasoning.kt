package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Reasoning as State
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.inputAllowsQueuedRepeat
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
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
    onModel: () -> Unit,
    blocked: Boolean,
) {
    val decrease = inputs.firstOrNull { it.id.endsWith("ccw") }
    val increase = inputs.firstOrNull { it.id.endsWith("cw") && !it.id.endsWith("ccw") }
    val pending = inputs.any { input ->
        inFlightIds.any { it.startsWith("input:${input.id}:") } && !snapshot.inputAllowsQueuedRepeat(input.id)
    }
    val modelEnabled = !blocked && !pending && snapshot.desktop?.foreground == true && snapshot.desktop.question == null
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1.25f)
                .height(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .clickable(enabled = modelEnabled, onClick = onModel)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                state.modelLabel.ifBlank { "Choose model" },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(8.dp))
        Step(decrease, Icons.Default.Remove, snapshot, inFlightIds, onInput, blocked)
        Column(
            modifier = Modifier.weight(0.85f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                state.level?.displayLabel ?: state.label.ifBlank { "Unavailable" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Step(increase, Icons.Default.Add, snapshot, inFlightIds, onInput, blocked)
    }
}

@Composable
private fun Step(
    input: Input?,
    icon: ImageVector,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    blocked: Boolean,
) {
    val gestures = if (input == null) emptyList() else Gesture.Kind.entries.filter { snapshot.inputEnabled(input.id, it) }
    val pending = input?.let { candidate ->
        inFlightIds.any { it.startsWith("input:${candidate.id}:") } && !snapshot.inputAllowsQueuedRepeat(candidate.id)
    } == true
    val enabled = !blocked && input != null && gestures.isNotEmpty() && !pending
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .combinedClickable(
                enabled = enabled,
                onClick = { if (Gesture.Kind.TAP in gestures) input?.id?.let { onInput(it, Gesture.Kind.TAP) } },
                onDoubleClick = if (Gesture.Kind.DOUBLE_TAP in gestures) {
                    { input?.id?.let { onInput(it, Gesture.Kind.DOUBLE_TAP) } }
                } else null,
                onLongClick = if (Gesture.Kind.HOLD in gestures) {
                    { input?.id?.let { onInput(it, Gesture.Kind.HOLD) } }
                } else null,
            )
            .alpha(if (input != null && gestures.isNotEmpty()) 1f else 0.36f),
        contentAlignment = Alignment.Center,
    ) {
        if (pending) {
            CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = input?.label, modifier = Modifier.size(25.dp))
        }
    }
}
