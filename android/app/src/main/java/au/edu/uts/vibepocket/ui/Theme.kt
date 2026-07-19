package au.edu.uts.vibepocket.ui

import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors: ColorScheme = darkColorScheme(
    primary = Color(0xFF55D6A4),
    onPrimary = Color(0xFF062A1C),
    primaryContainer = Color(0xFF153B2B),
    secondary = Color(0xFFE8B86F),
    onSecondary = Color(0xFF2E1D04),
    secondaryContainer = Color(0xFF493419),
    tertiary = Color(0xFF65C9E8),
    background = Color(0xFF0E1210),
    onBackground = Color(0xFFF0F4F1),
    surface = Color(0xFF18201B),
    onSurface = Color(0xFFF0F4F1),
    surfaceVariant = Color(0xFF29322D),
    onSurfaceVariant = Color(0xFFC1CBC4),
    error = Color(0xFFFF8877),
    onError = Color(0xFF3B0904),
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
