package au.edu.uts.vibepocket.ui.control

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class Layout(
    val maxWidth: Dp,
    val horizontalPadding: Dp,
    val gap: Dp,
    val agents: Dp,
    val agentAction: Dp,
    val contextGap: Dp,
    val status: Dp,
    val focusAction: Dp,
    val layers: Dp,
    val workflows: Dp,
    val pad: Dp,
    val direction: Dp,
    val center: Dp,
    val actionGap: Dp,
    val safety: Dp,
    val selectors: Dp,
) {
    val context: Dp get() = agents + contextGap + status
    val actions: Dp get() = pad + gap + safety
    val content: Dp get() = context + layers + workflows + actions + selectors + gap * 4

    companion object {
        fun of(availableHeight: Dp): Layout {
            val expanded = Layout(
                maxWidth = 393.dp,
                horizontalPadding = 16.dp,
                gap = 7.dp,
                agents = 58.dp,
                agentAction = 56.dp,
                contextGap = 4.dp,
                status = 50.dp,
                focusAction = 64.dp,
                layers = 60.dp,
                workflows = 72.dp,
                pad = 244.dp,
                direction = 80.dp,
                center = 56.dp,
                actionGap = 12.dp,
                safety = 56.dp,
                selectors = 64.dp,
            )
            if (availableHeight >= expanded.content) return expanded
            return Layout(
                maxWidth = 393.dp,
                horizontalPadding = 12.dp,
                gap = 6.dp,
                agents = 52.dp,
                agentAction = 52.dp,
                contextGap = 4.dp,
                status = 48.dp,
                focusAction = 56.dp,
                layers = 52.dp,
                workflows = 64.dp,
                pad = 228.dp,
                direction = 68.dp,
                center = 54.dp,
                actionGap = 8.dp,
                safety = 52.dp,
                selectors = 60.dp,
            )
        }
    }
}
