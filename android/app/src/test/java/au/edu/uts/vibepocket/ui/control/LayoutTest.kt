package au.edu.uts.vibepocket.ui.control

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    @Test
    fun xiaomiHeightUsesTheMeasuredBoardWithoutOverflow() {
        val layout = Layout.of(650.dp)

        assertEquals(244.dp, layout.pad)
        assertEquals(72.dp, layout.workflows)
        assertEquals(64.dp, layout.selectors)
        assertEquals(81.dp, layout.direction)
        assertEquals(643.dp, layout.content)
        assertTrue(layout.content <= 650.dp)
    }

    @Test
    fun compactPortraitContinuouslyAbsorbsAvailableHeight() {
        val floor = Layout.of(590.dp)
        val middle = Layout.of(620.dp)
        val ceiling = Layout.of(642.dp)

        assertEquals(590.dp, floor.content)
        assertEquals(620f, middle.content.value, 0.01f)
        assertEquals(642f, ceiling.content.value, 0.01f)
        assertTrue(floor.pad < middle.pad)
        assertTrue(middle.pad < ceiling.pad)
    }

    @Test
    fun tallerPortraitInvestsSpareHeightInFrequentControls() {
        val layout = Layout.of(715.dp)
        val boardWidth = 393.dp - layout.horizontalPadding * 2f
        val actionWidth = boardWidth - layout.pad - layout.actionGap

        assertEquals(715f, layout.content.value, 0.01f)
        assertEquals(270.dp, layout.pad)
        assertTrue(layout.direction > 81.dp)
        assertTrue(layout.workflows > 72.dp)
        assertTrue(layout.safety > 56.dp)
        assertTrue(layout.selectors > 64.dp)
        assertTrue(actionWidth >= 72.dp)
    }

    @Test
    fun compactLandscapeKeepsTheMinimumControlGeometry() {
        val layout = Layout.landscape(860.dp, 324.dp)

        assertEquals(324.dp, layout.landscapeLeft)
        assertEquals(324.dp, layout.information)
        assertEquals(324.dp, layout.landscapeRight)
        assertEquals(204.dp, layout.pad)
        assertEquals(76.dp, layout.workflows)
        assertTrue(layout.landscapeLeft <= 324.dp)
        assertTrue(layout.landscapeRight <= 324.dp)
        assertTrue(layout.direction >= 48.dp)
        assertTrue(layout.voice >= 48.dp)
    }

    @Test
    fun tallerLandscapeInvestsTheAvailableHeightInControls() {
        val layout = Layout.landscape(873.dp, 393.dp)

        assertEquals(393.dp, layout.landscapeLeft)
        assertEquals(393.dp, layout.landscapeRight)
        assertTrue(layout.pad > 204.dp)
        assertTrue(layout.direction > 68.dp)
        assertTrue(layout.workflows > 76.dp)
        assertTrue(layout.voice > 54.dp)
    }

    @Test
    fun narrowLandscapeProtectsTheTwoColumnActionTargets() {
        val layout = Layout.landscape(640.dp, 360.dp)
        val columnWidth = (640.dp - layout.horizontalPadding * 2f - 12.dp) / 2f
        val actionSlotWidth = (columnWidth - layout.pad - layout.actionGap - layout.gap) / 2f

        assertTrue(actionSlotWidth >= 48.dp)
        assertTrue(layout.direction >= 48.dp)
        assertEquals(360.dp, layout.landscapeRight)
    }
}
