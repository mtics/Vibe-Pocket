package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Model as State
import au.edu.uts.vibepocket.control.Snapshot
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SmartToy
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun Model(
    state: State,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onModel: (String) -> Boolean,
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val pending = inFlightIds.any { it.startsWith("model:") }
    val enabled = modelSelectionAllowed(
        blocked = blocked,
        pending = pending,
        capability = snapshot.capabilities.model,
        available = state.available,
        hasQuestion = snapshot.desktop?.question != null,
        foreground = snapshot.desktop?.foreground == true,
        fresh = snapshot.transportFresh,
    )
    var showOptions by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        if (!enabled) showOptions = false
    }

    Row(
        modifier = modifier
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { if (enabled) showOptions = true }
            .alpha(if (enabled) 1f else 0.62f)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.SmartToy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text("Model", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(
                state.label.ifBlank { "Choose" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        if (pending) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }

    if (showOptions) {
        ModalBottomSheet(onDismissRequest = { showOptions = false }) {
            Text(
                "Choose model",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            state.options.forEach { option ->
                val optionPending = inFlightIds.contains("model:${option.id}")
                val optionEnabled = enabled && !optionPending && !option.selected
                ListItem(
                    headlineContent = { Text(option.label) },
                    supportingContent = if (option.selected) ({ Text("Current model") }) else null,
                    trailingContent = when {
                        optionPending -> ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) })
                        option.selected -> ({ Icon(Icons.Default.Check, contentDescription = "Selected") })
                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth().clickable(enabled = optionEnabled) {
                        if (optionEnabled && onModel(option.id)) showOptions = false
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

internal fun modelSelectionAllowed(
    blocked: Boolean,
    pending: Boolean,
    capability: Boolean,
    available: Boolean,
    hasQuestion: Boolean,
    foreground: Boolean,
    fresh: Boolean,
): Boolean = !blocked && !pending && capability && available && !hasQuestion && foreground && fresh
