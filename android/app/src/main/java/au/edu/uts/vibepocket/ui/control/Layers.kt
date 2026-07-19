package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.profile.Layer
import au.edu.uts.vibepocket.ui.profileColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun Layers(
    layers: List<Layer>,
    active: String?,
    inFlightIds: Set<String>,
    enabled: Boolean,
    onLayer: (String) -> Boolean,
    shift: Boolean,
    onShift: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Shift(shift, enabled, onShift, Modifier.weight(1f))
        layers.forEachIndexed { index, layer ->
            val selected = layer.id == active
            val color = profileColor(layer.color)
            val loading = "layer:${layer.id}" in inFlightIds
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                    .border(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                        RoundedCornerShape(8.dp),
                    )
                    .semantics {
                        role = Role.Button
                        this.selected = selected
                        contentDescription = "Layer ${index + 1}, ${layer.name}"
                    }
                    .clickable(enabled = enabled && !selected && !loading, onClick = { onLayer(layer.id) }),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(17.dp), color = color, strokeWidth = 2.dp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                        Text("${index + 1}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Shift(
    pressed: Boolean,
    enabled: Boolean,
    onPressed: (Boolean) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = if (pressed) 1f else 0.38f), RoundedCornerShape(8.dp))
            .semantics {
                role = Role.Button
                contentDescription = "Layer shift"
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onPressed(true)
                    try {
                        var held = true
                        while (held) {
                            held = awaitPointerEvent().changes.firstOrNull { it.id == down.id }?.pressed == true
                        }
                    } finally {
                        onPressed(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
            Text("L1", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
