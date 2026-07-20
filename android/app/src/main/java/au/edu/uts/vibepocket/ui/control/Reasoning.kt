package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Reasoning as State
import au.edu.uts.vibepocket.control.Snapshot
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun Reasoning(
    state: State,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onReasoning: (State.Level) -> Boolean,
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val pending = inFlightIds.any { it.startsWith("reasoning:") }
    val options = state.options.ifEmpty {
        listOfNotNull(state.decreaseTo, state.level, state.increaseTo).distinct()
    }
    val enabled = !blocked && !pending && snapshot.transportFresh && snapshot.capabilities.reasoning &&
        state.available && options.isNotEmpty() && snapshot.desktop?.foreground == true &&
        snapshot.desktop.question == null && snapshot.desktop.voice?.active != true
    var showOptions by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) showOptions = false }

    Row(
        modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .alpha(if (enabled || pending) 1f else 0.58f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Step(state.decreaseTo, Icons.Default.Remove, enabled, pending, onReasoning)
        Column(
            Modifier.weight(1f).clickable(enabled = enabled) { showOptions = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Reasoning", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(
                state.level?.displayLabel ?: state.label.ifBlank { "Unavailable" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Step(state.increaseTo, Icons.Default.Add, enabled, pending, onReasoning)
    }

    if (showOptions) {
        ModalBottomSheet(onDismissRequest = { showOptions = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Choose reasoning",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                options.forEach { level ->
                    val selected = level == state.level
                    val optionPending = "reasoning:${level.wireValue}" in inFlightIds
                    ListItem(
                        headlineContent = { Text(level.displayLabel) },
                        trailingContent = when {
                            optionPending -> ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) })
                            selected -> ({ Icon(Icons.Default.Check, contentDescription = "Selected") })
                            else -> null
                        },
                        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled && !selected && !optionPending) {
                            if (onReasoning(level)) showOptions = false
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Step(
    target: State.Level?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    pending: Boolean,
    onReasoning: (State.Level) -> Boolean,
) {
    Box(
        Modifier.size(48.dp).clickable(enabled = enabled && target != null) { target?.let(onReasoning) },
        contentAlignment = Alignment.Center,
    ) {
        if (pending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
    }
}
