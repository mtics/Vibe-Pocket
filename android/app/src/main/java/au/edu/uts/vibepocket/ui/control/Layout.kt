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
    val voice: Dp,
) {
    val context: Dp get() = agents + contextGap + status
    val actions: Dp get() = pad + gap + safety
    val content: Dp get() = context + layers + workflows + actions + selectors + gap * 4
    val information: Dp get() = context + layers + workflows + selectors + gap * 3
    val landscapeLeft: Dp get() = information
    val landscapeRight: Dp get() = actions + gap + voice

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
                direction = 81.dp,
                center = 56.dp,
                actionGap = 12.dp,
                safety = 56.dp,
                selectors = 64.dp,
                voice = 96.dp,
            )
            val compact = Layout(
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
                direction = 76.dp,
                center = 54.dp,
                actionGap = 8.dp,
                safety = 52.dp,
                selectors = 60.dp,
                voice = 96.dp,
            )
            if (availableHeight < compact.content) return compact
            if (availableHeight < expanded.content) {
                val progress = (availableHeight - compact.content).value /
                    (expanded.content - compact.content).value
                return compact.interpolate(expanded, progress)
            }

            val extra = (availableHeight - expanded.content).coerceIn(0.dp, MaximumPortraitGrowth)
            if (extra < MinimumPortraitGrowth) return expanded

            val padExtra = (extra * 0.46f).coerceAtMost(MaximumPortraitPad - expanded.pad)
            val safetyExtra = extra * 0.12f
            val selectorExtra = extra * 0.10f
            val workflowExtra = extra - padExtra - safetyExtra - selectorExtra
            return expanded.copy(
                workflows = expanded.workflows + workflowExtra,
                pad = expanded.pad + padExtra,
                direction = expanded.direction + padExtra / 3f,
                safety = expanded.safety + safetyExtra,
                selectors = expanded.selectors + selectorExtra,
            )
        }

        fun landscape(availableWidth: Dp = 860.dp, availableHeight: Dp = 324.dp): Layout {
            val base = Layout(
                maxWidth = 860.dp,
                horizontalPadding = 12.dp,
                gap = 6.dp,
                agents = 54.dp,
                agentAction = 54.dp,
                contextGap = 4.dp,
                status = 54.dp,
                focusAction = 54.dp,
                layers = 56.dp,
                workflows = 76.dp,
                pad = 204.dp,
                direction = 68.dp,
                center = 54.dp,
                actionGap = 8.dp,
                safety = 54.dp,
                selectors = 62.dp,
                voice = 54.dp,
            )
            val extra = (availableHeight - base.landscapeRight).coerceIn(0.dp, 72.dp)
            if (extra == 0.dp) return base

            val agentExtra = extra * 0.12f
            val statusExtra = extra * 0.12f
            val layerExtra = extra * 0.18f
            val workflowExtra = extra * 0.29f
            val selectorExtra = extra - agentExtra - statusExtra - layerExtra - workflowExtra
            val columnWidth = (
                availableWidth.coerceAtMost(base.maxWidth) -
                    base.horizontalPadding * 2f - LandscapeColumnGap
                ) / 2f
            val minimumActionGrid = MinimumTarget * 2f + base.gap
            val widthLimitedPad = columnWidth - base.actionGap - minimumActionGrid
            val expandedPad = (base.pad + extra * 0.5f)
                .coerceAtMost(widthLimitedPad)
                .coerceAtLeast(MinimumPad)
            val bottomHeight = base.landscapeRight + extra - base.gap * 2f - expandedPad
            val bottomExtra = bottomHeight - base.safety - base.voice
            val safetyExtra = bottomExtra * 0.35f
            val voiceExtra = bottomExtra - safetyExtra
            val expandedDirection = expandedPad / 3f

            return base.copy(
                agents = base.agents + agentExtra,
                status = base.status + statusExtra,
                layers = base.layers + layerExtra,
                workflows = base.workflows + workflowExtra,
                pad = expandedPad,
                direction = expandedDirection,
                center = (base.center + extra * 0.1f).coerceAtMost(expandedDirection),
                safety = base.safety + safetyExtra,
                selectors = base.selectors + selectorExtra,
                voice = base.voice + voiceExtra,
            )
        }

        private val LandscapeColumnGap = 12.dp
        private val MinimumTarget = 48.dp
        private val MinimumPad = MinimumTarget * 3f
        private val MinimumPortraitGrowth = 12.dp
        private val MaximumPortraitGrowth = 72.dp
        private val MaximumPortraitPad = 270.dp

        private fun Layout.interpolate(target: Layout, fraction: Float): Layout = copy(
            horizontalPadding = interpolate(horizontalPadding, target.horizontalPadding, fraction),
            gap = interpolate(gap, target.gap, fraction),
            agents = interpolate(agents, target.agents, fraction),
            agentAction = interpolate(agentAction, target.agentAction, fraction),
            contextGap = interpolate(contextGap, target.contextGap, fraction),
            status = interpolate(status, target.status, fraction),
            focusAction = interpolate(focusAction, target.focusAction, fraction),
            layers = interpolate(layers, target.layers, fraction),
            workflows = interpolate(workflows, target.workflows, fraction),
            pad = interpolate(pad, target.pad, fraction),
            direction = interpolate(direction, target.direction, fraction),
            center = interpolate(center, target.center, fraction),
            actionGap = interpolate(actionGap, target.actionGap, fraction),
            safety = interpolate(safety, target.safety, fraction),
            selectors = interpolate(selectors, target.selectors, fraction),
            voice = interpolate(voice, target.voice, fraction),
        )

        private fun interpolate(start: Dp, end: Dp, fraction: Float): Dp =
            start + (end - start) * fraction
    }
}
