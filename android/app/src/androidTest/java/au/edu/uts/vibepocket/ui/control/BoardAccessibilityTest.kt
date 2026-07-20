package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Agent
import au.edu.uts.vibepocket.control.Question
import au.edu.uts.vibepocket.control.Status
import au.edu.uts.vibepocket.control.Tasks
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun repeatingDirectionRemainsActionableForAccessibility() {
        rule.setContent { BoardPreview(Fixtures.snapshot()) }

        rule.onNodeWithContentDescription("Up", substring = true)
            .assertIsEnabled()
            .assertHasClickAction()
    }

    @Test
    fun skipAgentsFocusesStatusWithTwentyFourTasksAndNoNextBinding() {
        rule.setContent {
            BoardPreview(Fixtures.snapshot(agentCount = 24, focusNext = false))
        }

        val skip = rule.onNodeWithContentDescription("Active Codex tasks")
            .fetchSemanticsNode().config
            .getOrElse(SemanticsActions.CustomActions) { emptyList() }
            .first { it.label == "Skip agents" }
        rule.runOnIdle { assertTrue(skip.action()) }

        rule.onNode(hasStateDescription("Ready. Vibe Pocket UI")).assertIsFocused()
    }

    @Test
    fun staleTaskRemainsVisibleButCannotReceiveAnAgentAction() {
        val snapshot = Fixtures.snapshot()
        val desktop = requireNotNull(snapshot.desktop)
        val stale = snapshot.copy(
            desktop = desktop.copy(
                tasks = Tasks(Tasks.Availability.STALE, "Catalog offline"),
                agents = desktop.agents.map { agent ->
                    agent.copy(
                        freshness = Agent.Freshness.STALE,
                        actionable = false,
                    )
                },
            ),
        )
        rule.setContent { BoardPreview(stale) }

        rule.onNodeWithContentDescription("Vibe Pocket UI, Last known, unavailable")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun everyActionableTargetMeetsThe48DpBaseline() {
        rule.setContent { BoardPreview(Fixtures.snapshot()) }
        val density = rule.activity.resources.displayMetrics.density
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val undersized = nodes.mapNotNull { node ->
            val bounds = node.touchBoundsInRoot
            val small = bounds.width / density < 48f || bounds.height / density < 48f
            if (!small) null else {
                val label = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                    .joinToString()
                    .ifBlank { "Unlabelled" }
                "$label: $bounds"
            }
        }

        assertTrue("Undersized actionable bounds: $undersized", undersized.isEmpty())
    }

    @Test
    fun coreControlBoundsStayFixedWhileWorkStateChanges() {
        var snapshot by mutableStateOf(Fixtures.snapshot())
        rule.setContent { BoardPreview(snapshot) }
        val controls = listOf(
            hasContentDescription("Mode, Default"),
            hasContentDescription("Model, GPT-5.4"),
            hasContentDescription("Reasoning, Medium"),
            hasContentDescription("Layer 1: Default"),
            hasContentDescription("Up", substring = true),
            hasContentDescription("Voice", substring = true),
        )
        fun bounds() = controls.map { rule.onNode(it).fetchSemanticsNode().boundsInRoot }
        val ready = bounds()

        rule.runOnIdle {
            snapshot = Fixtures.snapshot(activity = Activity.EXECUTING, message = "Running tests")
        }

        assertEquals(ready, bounds())
    }

    @Test
    fun reasoningTargetIsAnnouncedWithoutReplacingTheConfirmedValue() {
        rule.setContent {
            BoardPreview(
                Fixtures.snapshot(),
                reasoningTarget = au.edu.uts.vibepocket.control.Reasoning.Level.HIGH,
            )
        }

        rule.onNodeWithContentDescription("Reasoning, Medium, changing to High")
            .assertIsDisplayed()
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
