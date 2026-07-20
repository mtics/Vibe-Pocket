package au.edu.uts.vibepocket.ui.control.stage

import au.edu.uts.vibepocket.ui.colorFor
import au.edu.uts.vibepocket.ui.control.state.State
import au.edu.uts.vibepocket.ui.iconFor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun Stage(state: State, modifier: Modifier = Modifier) {
    val accent = colorFor(state.activity)
    val largeText = au.edu.uts.vibepocket.ui.control.largeText(LocalDensity.current.fontScale)
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = listOfNotNull(state.title, state.task, state.detail).joinToString(". ")
            },
    ) {
        Box(Modifier.fillMaxHeight().width(4.dp).background(accent))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(iconFor(state.activity), contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = if (largeText) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!largeText) {
                    Text(
                        state.task ?: state.selection ?: state.detail.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (!largeText) state.meta?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
