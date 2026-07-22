package au.edu.uts.vibepocket

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LiveControllerTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun requireExplicitLiveRun() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
    }

    @Test
    fun reasoningRoundTripChangesTheVisibleDesktopTask() {
        waitFor("Reasoning, High")

        rule.onNodeWithContentDescription("Increase reasoning to Extra high").performClick()
        waitFor("Reasoning, Extra high")

        rule.onNodeWithContentDescription("Decrease reasoning to High").performClick()
        waitFor("Reasoning, High")
    }

    @Test
    fun modelRoundTripChangesTheVisibleDesktopTask() {
        waitFor("Model, 5.6 Sol")

        rule.onNodeWithContentDescription("Model, 5.6 Sol").performClick()
        rule.onNodeWithText("5.6 Terra").performClick()
        waitFor("Model, 5.6 Terra")

        rule.onNodeWithContentDescription("Model, 5.6 Terra").performClick()
        rule.onNodeWithText("5.6 Sol").performClick()
        waitFor("Model, 5.6 Sol")
    }

    private fun waitFor(description: String) {
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(rule.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty())
    }
}
