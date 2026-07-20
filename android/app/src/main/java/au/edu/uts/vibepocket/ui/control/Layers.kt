package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.profile.Layer
import au.edu.uts.vibepocket.ui.compositedBackground
import au.edu.uts.vibepocket.ui.contrastingColor
import au.edu.uts.vibepocket.ui.layerSemanticsLabel
import au.edu.uts.vibepocket.ui.profileColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
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
    val largeText = largeText(LocalDensity.current.fontScale)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        layers.take(6).forEachIndexed { index, layer ->
            val selected = layer.id == active
            val loading = "layer:${layer.id}" in inFlightIds
            val accentSource = profileColor(layer.color)
            val surface = MaterialTheme.colorScheme.surface
            val background = if (selected) compositedBackground(accentSource, 0.16f, surface) else surface
            val accent = contrastingColor(accentSource, background, MaterialTheme.colorScheme.onSurface, 3f)
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(8.dp))
                    .background(background)
                    .border(1.dp, if (selected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.42f), RoundedCornerShape(8.dp))
                    .semantics {
                        role = Role.Button
                        this.selected = selected
                        contentDescription = layerSemanticsLabel(index, layer.name)
                    }
                    .clickable(enabled = enabled && !selected && !loading) { onLayer(layer.id) }
                    .alpha(if (enabled || selected) 1f else 0.58f)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Text(
                        if (largeText) "${index + 1}" else compactLayerName(layer.name, index),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private fun compactLayerName(name: String, index: Int): String {
    val value = name.trim()
    if (value.isEmpty()) return "${index + 1}"
    val generic = Regex("(?i)^layer\\s*${index + 1}$")
    return if (generic.matches(value)) "${index + 1}" else value
}
