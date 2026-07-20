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
    fun shorterMainSurfaceKeepsTheCompactGeometryStable() {
        val first = Layout.of(620.dp)
        val second = Layout.of(600.dp)

        assertEquals(first, second)
        assertEquals(228.dp, first.pad)
        assertEquals(76.dp, first.direction)
        assertEquals(590.dp, first.content)
        assertTrue(first.content <= 600.dp)
    }

    @Test
    fun landscapeUsesTheFullBoardForControls() {
        val layout = Layout.landscape()

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
}
