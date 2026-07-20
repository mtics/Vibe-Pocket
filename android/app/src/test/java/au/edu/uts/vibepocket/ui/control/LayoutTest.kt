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
        assertEquals(643.dp, layout.content)
        assertTrue(layout.content <= 650.dp)
    }

    @Test
    fun shorterMainSurfaceKeepsTheCompactGeometryStable() {
        val first = Layout.of(620.dp)
        val second = Layout.of(600.dp)

        assertEquals(first, second)
        assertEquals(228.dp, first.pad)
        assertEquals(590.dp, first.content)
        assertTrue(first.content <= 600.dp)
    }
}
