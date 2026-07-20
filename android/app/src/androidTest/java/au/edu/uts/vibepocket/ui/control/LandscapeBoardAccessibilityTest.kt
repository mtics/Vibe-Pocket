package au.edu.uts.vibepocket.ui.control

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LandscapeBoardAccessibilityTest {
    @get:Rule
    val rule = createAndroidComposeRule<LandscapeBoardTestActivity>()

    @Before
    fun enableAccessibilityValidation() {
        rule.enableAccessibilityChecks()
    }

    @Test
    fun landscapeStartsWithControlsInsteadOfAProductTitleBar() {
        rule.setContent { BoardPreview(Fixtures.snapshot(), landscape = true) }

        rule.onAllNodesWithText("Vibe Pocket").assertCountEquals(0)
        rule.onNodeWithContentDescription("Open settings").assertIsDisplayed()
        rule.onNodeWithContentDescription("Mode, Default").assertIsDisplayed()
        rule.onNodeWithContentDescription("Voice", substring = true).assertIsDisplayed()
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun landscapeActionableTargetsMeetThe48DpBaseline() {
        rule.setContent { BoardPreview(Fixtures.snapshot(), landscape = true) }
        val density = rule.activity.resources.displayMetrics.density
        val undersized = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .map { it.touchBoundsInRoot }
            .filter { bounds -> bounds.width / density < 48f || bounds.height / density < 48f }

        assertTrue("Undersized landscape touch bounds: $undersized", undersized.isEmpty())
    }
}
