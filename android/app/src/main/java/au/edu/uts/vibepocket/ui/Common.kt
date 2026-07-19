package au.edu.uts.vibepocket.ui

import android.os.Build
import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.profile.Input
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

@Composable
internal fun Section(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(16.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun ErrorNotice(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Error, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun Loading(isRefreshing: Boolean, error: String?, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isRefreshing) CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(12.dp))
        Text(error ?: if (isRefreshing) "Connecting to Bridge..." else "No controller state yet")
        if (!isRefreshing) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onRefresh, shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

internal fun joystickInput(
    x: Float,
    y: Float,
    size: IntSize,
    inputs: Map<String, Input>,
): Input? {
    val dx = x - size.width / 2f
    val dy = y - size.height / 2f
    if (abs(dx) < min(size.width, size.height) * 0.12f && abs(dy) < min(size.width, size.height) * 0.12f) return null
    val direction = if (abs(dx) > abs(dy)) {
        if (dx > 0) "right" else "left"
    } else {
        if (dy > 0) "down" else "up"
    }
    return inputs[direction]
}

@Composable
internal fun colorFor(state: Activity): Color = when (state) {
    Activity.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    Activity.UNREAD -> contrastingColor(
        preferred = UnreadColor,
        background = MaterialTheme.colorScheme.surface,
        fallback = MaterialTheme.colorScheme.tertiary,
        minimumRatio = 3f,
    )
    Activity.THINKING -> MaterialTheme.colorScheme.tertiary
    Activity.EXECUTING -> MaterialTheme.colorScheme.primary
    Activity.WAITING -> MaterialTheme.colorScheme.secondary
    Activity.COMPLETE -> MaterialTheme.colorScheme.primary
    Activity.ERROR -> MaterialTheme.colorScheme.error
}

internal fun iconFor(state: Activity): ImageVector = when (state) {
    Activity.IDLE -> Icons.Filled.RadioButtonUnchecked
    Activity.UNREAD -> Icons.Filled.MarkChatUnread
    Activity.THINKING -> Icons.Filled.Psychology
    Activity.EXECUTING -> Icons.Filled.PlayArrow
    Activity.WAITING -> Icons.Filled.HourglassTop
    Activity.COMPLETE -> Icons.Filled.Done
    Activity.ERROR -> Icons.Filled.Error
}

internal fun labelFor(state: Activity): String = when (state) {
    Activity.IDLE -> "Idle"
    Activity.UNREAD -> "Unread"
    Activity.THINKING -> "Thinking"
    Activity.EXECUTING -> "Running"
    Activity.WAITING -> "Needs input"
    Activity.COMPLETE -> "Complete"
    Activity.ERROR -> "Error"
}

internal fun iconForInput(name: String): ImageVector = when (name) {
    "check" -> Icons.Filled.Check
    "close" -> Icons.Filled.Close
    "mic" -> Icons.Filled.Mic
    "add" -> Icons.Filled.Add
    "stop" -> Icons.Filled.Stop
    "cycle" -> Icons.Filled.SwapHoriz
    "clear" -> Icons.AutoMirrored.Filled.Backspace
    "agent" -> Icons.Filled.AccountCircle
    "up" -> Icons.Filled.ArrowUpward
    "down" -> Icons.Filled.ArrowDownward
    "left" -> Icons.AutoMirrored.Filled.ArrowBack
    "right" -> Icons.AutoMirrored.Filled.ArrowForward
    "focus" -> Icons.Filled.CenterFocusStrong
    "touch" -> Icons.Filled.TouchApp
    "review" -> Icons.Filled.RateReview
    "debug" -> Icons.Filled.BugReport
    "refactor" -> Icons.Filled.Build
    "test" -> Icons.Filled.Science
    else -> Icons.Filled.CenterFocusStrong
}

internal fun iconForDirection(inputId: String): ImageVector = when {
    inputId.endsWith("_up") -> Icons.Filled.ArrowUpward
    inputId.endsWith("_down") -> Icons.Filled.ArrowDownward
    inputId.endsWith("_left") -> Icons.AutoMirrored.Filled.ArrowBack
    else -> Icons.AutoMirrored.Filled.ArrowForward
}

internal fun profileColor(value: String?): Color = runCatching {
    Color(android.graphics.Color.parseColor(value))
}.getOrDefault(CompleteColor)

internal fun contrastRatio(foreground: Color, background: Color): Float {
    val lighter = maxOf(foreground.luminance(), background.luminance())
    val darker = minOf(foreground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

internal fun contrastingColor(
    preferred: Color,
    background: Color,
    fallback: Color,
    minimumRatio: Float = 4.5f,
): Color = if (contrastRatio(preferred, background) >= minimumRatio) preferred else fallback

internal fun compositedBackground(foreground: Color, alpha: Float, background: Color): Color =
    foreground.copy(alpha = alpha).compositeOver(background)

internal fun layerSemanticsLabel(index: Int, name: String): String {
    val ordinal = "Layer ${index + 1}"
    val trimmed = name.trim()
    return if (trimmed.isBlank() || trimmed.equals(ordinal, ignoreCase = true)) ordinal else "$ordinal: $trimmed"
}
