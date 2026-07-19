package au.edu.uts.vibepocket.ui

import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors: ColorScheme = darkColorScheme(
    primary = Color(0xFF62E0B2),
    onPrimary = Color(0xFF05291D),
    primaryContainer = Color(0xFF143A2E),
    onPrimaryContainer = Color(0xFFB9F5DD),
    secondary = Color(0xFFF2C66D),
    onSecondary = Color(0xFF302300),
    secondaryContainer = Color(0xFF2A2D32),
    onSecondaryContainer = Color(0xFFF5E2B8),
    tertiary = Color(0xFF79B9FF),
    onTertiary = Color(0xFF062B4C),
    tertiaryContainer = Color(0xFF17334A),
    background = Color(0xFF0B0E11),
    onBackground = Color(0xFFF2F4F6),
    surface = Color(0xFF14191E),
    onSurface = Color(0xFFF2F4F6),
    surfaceVariant = Color(0xFF22292F),
    onSurfaceVariant = Color(0xFFB8C1C8),
    outline = Color(0xFF46515A),
    error = Color(0xFFFF8B83),
    onError = Color(0xFF3D0704),
)

internal val IdleColor = Color(0xFF9AA39D)
internal val UnreadColor = Color(0xFFF08BC1)
internal val ThinkingColor = Color(0xFFB9A7FF)
internal val ExecutingColor = Color(0xFF59C7F2)
internal val WaitingColor = Color(0xFFF2B95F)
internal val CompleteColor = Color(0xFF55D6A4)
internal val ErrorColor = Color(0xFFFF776B)
internal val LayerColors = listOf(
    "#F4F4F2", "#A020F0", "#25D9E8", "#FF8C24",
    "#FF4F9A", "#FFE04A", "#55D6A4", "#FF776B",
)

@Composable
internal fun Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
