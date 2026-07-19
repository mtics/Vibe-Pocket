package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.agentSlots
import au.edu.uts.vibepocket.ui.colorFor
import au.edu.uts.vibepocket.ui.iconFor
import au.edu.uts.vibepocket.ui.labelFor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun Agents(
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onAgent: (String) -> Unit,
) {
    val slots = snapshot.agentSlots().filter { it.agent != null }
    if (slots.isEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("No active Codex tasks", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            slots.forEach { slot ->
                val agent = requireNotNull(slot.agent)
                val color = colorFor(agent.activity)
                val loading = "agent:${agent.id}" in inFlightIds
                Row(
                    modifier = Modifier
                        .width(148.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (slot.focused) color.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface)
                        .border(1.dp, color.copy(alpha = if (slot.focused) 0.9f else 0.28f), RoundedCornerShape(8.dp))
                        .semantics {
                            role = Role.Button
                            selected = slot.focused
                        }
                        .clickable(enabled = slot.canFocus && !loading, onClick = { onAgent(agent.id) })
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
                    } else {
                        Icon(iconFor(agent.activity), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            agent.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(labelFor(agent.activity), color = color, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
