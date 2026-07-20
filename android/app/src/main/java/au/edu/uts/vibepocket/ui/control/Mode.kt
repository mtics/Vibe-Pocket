package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun Mode(
    state: Selector,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onMode: (String) -> Boolean,
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val pending = inFlightIds.any { it.startsWith("mode:") }
    val target = state.options.firstOrNull { it.id != state.id }
    val enabled = !blocked && !pending && snapshot.transportFresh &&
        snapshot.capabilities.modeCycle && state.available && target != null &&
        snapshot.desktop?.foreground == true && snapshot.desktop.question == null &&
        snapshot.desktop.voice?.active != true
    Box(
        modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.34f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { target?.let { onMode(it.id) } }
            .alpha(if (enabled || pending) 1f else 0.58f)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (pending) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                Text("Mode", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(
                    state.label.ifBlank { "Unavailable" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
