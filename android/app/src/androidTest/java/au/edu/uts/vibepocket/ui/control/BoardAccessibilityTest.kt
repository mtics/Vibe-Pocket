package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Status
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoardAccessibilityTest {
    @get:Rule
    val rule = createAndroidComposeRule<BoardTestActivity>()

    @Before
    fun enableAccessibilityValidation() {
        rule.enableAccessibilityChecks()
    }

    @Test
    fun readyBoardPassesAutomatedChecks() {
        rule.setContent { BoardPreview(Fixtures.snapshot()) }
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun staleBoardPassesAutomatedChecks() {
        rule.setContent { BoardPreview(Fixtures.snapshot().copy(transportFresh = false)) }
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun darkBoardPassesAutomatedChecks() {
        rule.setContent { BoardPreview(Fixtures.snapshot(), dark = true) }
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun statusDetailsRevealTheFullBridgeError() {
        val detail = "Unlock the M5 before using Vibe Pocket desktop controls."
        val snapshot = Fixtures.snapshot().copy(
            status = Status("degraded", detail),
            desktop = null,
        )

        rule.setContent { BoardPreview(snapshot) }
        rule.onNodeWithText("Bridge unavailable").performClick()
        rule.onNodeWithText(detail).assertIsDisplayed()
        rule.onRoot().tryPerformAccessibilityChecks()
    }
}
