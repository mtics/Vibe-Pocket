package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.profile.Layer
import au.edu.uts.vibepocket.ui.profileColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun Layers(
    layers: List<Layer>,
    active: String?,
    inFlightIds: Set<String>,
    enabled: Boolean,
    onLayer: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    if (layers.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        layers.chunked(3).forEachIndexed { rowIndex, rowLayers ->
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowLayers.forEachIndexed { columnIndex, layer ->
                    LayerButton(
                        index = rowIndex * 3 + columnIndex,
                        layer = layer,
                        active = layer.id == active,
                        loading = "layer:${layer.id}" in inFlightIds,
                        enabled = enabled,
                        onLayer = onLayer,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - rowLayers.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LayerButton(
    index: Int,
    layer: Layer,
    active: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onLayer: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val profileAccent = profileColor(layer.color)
    val accent = if (profileAccent.luminance() > 0.82f) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        profileAccent
    }
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = if (active) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
            )
            .semantics {
                role = Role.Button
                selected = active
                contentDescription = "Layer ${index + 1}, ${layer.name}"
            }
            .clickable(enabled = enabled && !active && !loading) { onLayer(layer.id) }
            .alpha(if (enabled || active) 1f else 0.62f)
            .padding(PaddingValues(horizontal = 10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(14.dp), color = accent, strokeWidth = 2.dp)
        } else {
            Row(Modifier.size(9.dp).clip(CircleShape).background(accent)) {}
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = layer.name.ifBlank { "Layer ${index + 1}" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
