package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.ui.colorFor
import au.edu.uts.vibepocket.ui.iconFor
import au.edu.uts.vibepocket.ui.labelFor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun Context(snapshot: Snapshot) {
    val desktop = snapshot.desktop
    val question = desktop?.question
    val activity = desktop?.activity ?: if (snapshot.status.state == "ready") Activity.IDLE else Activity.ERROR
    val tint = if (question == null) colorFor(activity) else MaterialTheme.colorScheme.secondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (question != null) {
            Icon(Icons.Default.HourglassTop, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${question.header}  ${question.index + 1}/${question.count}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    question.options.getOrNull(question.selectedOptionIndex)?.label ?: question.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            Item(iconFor(activity), "Task", labelFor(activity), tint, Modifier.weight(0.8f))
            Item(
                Icons.Default.Tune,
                "Mode",
                desktop?.mode?.label.orEmpty().ifBlank { "Unavailable" },
                null,
                Modifier.weight(1f),
            )
            Item(
                Icons.Default.Shield,
                "Access",
                desktop?.access?.label.orEmpty().ifBlank { "Unavailable" },
                null,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Item(icon: ImageVector, caption: String, value: String, tint: Color?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(7.dp))
        Column {
            Text(caption, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(
                value,
                color = tint ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
