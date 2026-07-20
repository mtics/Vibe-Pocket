package au.edu.uts.vibepocket.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF176B5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F1E9),
    onPrimaryContainer = Color(0xFF073B31),
    secondary = Color(0xFF8B5C12),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6E4BC),
    onSecondaryContainer = Color(0xFF342100),
    tertiary = Color(0xFF246B90),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD8ECF7),
    onTertiaryContainer = Color(0xFF0B354B),
    background = Color(0xFFF4F7F8),
    onBackground = Color(0xFF172126),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF172126),
    surfaceVariant = Color(0xFFE8EEF0),
    onSurfaceVariant = Color(0xFF59676F),
    outline = Color(0xFFA8B4BA),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF78D7C3),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005144),
    onPrimaryContainer = Color(0xFF95F4DE),
    secondary = Color(0xFFE6C277),
    onSecondary = Color(0xFF402D00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Color(0xFFFFDEA0),
    tertiary = Color(0xFF88CEEF),
    onTertiary = Color(0xFF003548),
    tertiaryContainer = Color(0xFF164D64),
    onTertiaryContainer = Color(0xFFC4E9FF),
    background = Color(0xFF101416),
    onBackground = Color(0xFFE0E4E6),
    surface = Color(0xFF171C1E),
    onSurface = Color(0xFFE0E4E6),
    surfaceVariant = Color(0xFF3F494D),
    onSurfaceVariant = Color(0xFFBFC8CC),
    outline = Color(0xFF899397),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

internal val IdleColor = Color(0xFF77827B)
internal val UnreadColor = Color(0xFFB54580)
internal val ThinkingColor = Color(0xFF2D6F9E)
internal val ExecutingColor = Color(0xFF087F73)
internal val WaitingColor = Color(0xFF9A6500)
internal val CompleteColor = Color(0xFF17755A)
internal val ErrorColor = Color(0xFFB3261E)
internal val LayerColors = listOf(
    "#F4F4F2", "#A020F0", "#25D9E8", "#FF8C24",
    "#FF4F9A", "#FFE04A", "#55D6A4", "#FF776B",
)

@Composable
internal fun Theme(dark: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
