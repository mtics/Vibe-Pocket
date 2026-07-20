package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Question
import au.edu.uts.vibepocket.control.Status
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
        rule.onAllNodesWithText(detail).assertCountEquals(1)
        rule.onNodeWithText("Bridge unavailable").performClick()
        rule.onAllNodesWithText(detail).assertCountEquals(2)
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun modePickerClosesWhenAQuestionArrives() {
        var snapshot by mutableStateOf(Fixtures.snapshot())
        rule.setContent { BoardPreview(snapshot) }

        rule.onNodeWithContentDescription("Mode, Default").performClick()
        rule.onNodeWithText("Plan").assertIsDisplayed()

        rule.runOnIdle {
            snapshot = snapshot.copy(
                desktop = snapshot.desktop?.copy(
                    question = Question(
                        index = 0,
                        count = 1,
                        header = "Choose scope",
                        text = "Which module should change?",
                        options = listOf(Question.Option("Android", "Only the phone app")),
                        selectedOptionIndex = 0,
                        hasSpokenAnswer = false,
                        isSecret = false,
                    ),
                ),
            )
        }

        rule.onAllNodesWithText("Plan").assertCountEquals(0)
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun modePickerClosesWhenTheFocusedTaskChanges() {
        var snapshot by mutableStateOf(Fixtures.snapshot())
        rule.setContent { BoardPreview(snapshot) }

        rule.onNodeWithContentDescription("Mode, Default").performClick()
        rule.onNodeWithText("Plan").assertIsDisplayed()

        rule.runOnIdle {
            snapshot = snapshot.copy(
                desktop = snapshot.desktop?.copy(
                    focusedAgentId = "agent-000000000000000000000002",
                ),
            )
        }

        rule.onAllNodesWithText("Plan").assertCountEquals(0)
    }
}
