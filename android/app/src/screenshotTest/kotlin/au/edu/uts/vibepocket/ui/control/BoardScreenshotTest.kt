package au.edu.uts.vibepocket.ui.control

import android.content.res.Configuration
import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Question
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Status
import au.edu.uts.vibepocket.session.Operation
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Ready", widthDp = 393, heightDp = 873, showBackground = true)
@Composable
fun readyPortrait() = BoardPreview(Fixtures.snapshot())

@PreviewTest
@Preview(name = "Running", widthDp = 393, heightDp = 873, showBackground = true)
@Composable
fun runningPortrait() = BoardPreview(Fixtures.snapshot(activity = Activity.EXECUTING, message = "Running tests"))

@PreviewTest
@Preview(name = "Question", widthDp = 393, heightDp = 873, showBackground = true)
@Composable
fun questionPortrait() = BoardPreview(
    Fixtures.snapshot(
        question = Question(
            index = 0,
            count = 2,
            header = "Choose scope",
            text = "Which module should change?",
            options = listOf(Question.Option("Android", "Only the phone app")),
            selectedOptionIndex = 0,
            hasSpokenAnswer = false,
            isSecret = false,
        ),
    ),
)

@PreviewTest
@Preview(name = "Decision", widthDp = 393, heightDp = 873, showBackground = true)
@Composable
fun decisionPortrait() = BoardPreview(Fixtures.snapshot(activity = Activity.WAITING, message = "Review the proposed changes"))

@PreviewTest
@Preview(name = "Task error", widthDp = 393, heightDp = 873, showBackground = true)
@Composable
fun errorPortrait() = BoardPreview(Fixtures.snapshot(activity = Activity.ERROR, message = "The current task is unavailable"))

@PreviewTest
@Preview(name = "Stale", widthDp = 393, heightDp = 873, showBackground = true)
@Composable
fun stalePortrait() = BoardPreview(Fixtures.snapshot().copy(transportFresh = false))

@PreviewTest
@Preview(name = "Offline", widthDp = 393, heightDp = 873, showBackground = true)
@Composable
fun offlinePortrait() = BoardPreview(
    Snapshot(
        revision = "offline",
        status = Status("degraded", "Unlock the M5 before using Vibe Pocket desktop controls."),
        capabilities = Capabilities(),
    ),
)

@PreviewTest
@Preview(name = "Outcome unknown", widthDp = 393, heightDp = 873, showBackground = true)
@Composable
fun unknownPortrait() = BoardPreview(
    Fixtures.snapshot(),
    operation = Operation(
        id = "unknown-operation",
        uiId = "input:key_accept:tap",
        phase = Operation.Phase.UNKNOWN,
        message = "The Mac may have completed this command. Check it before retrying.",
    ),
)

@PreviewTest
@Preview(name = "Voice active", widthDp = 393, heightDp = 873, showBackground = true)
@Composable
fun voicePortrait() = BoardPreview(Fixtures.snapshot(voiceActive = true))

@PreviewTest
@Preview(name = "Pending", widthDp = 393, heightDp = 873, showBackground = true)
@Composable
fun pendingPortrait() = BoardPreview(Fixtures.snapshot(), inFlightIds = setOf("input:key_accept:tap"))

@PreviewTest
@Preview(name = "Reasoning pending", widthDp = 393, heightDp = 873, showBackground = true)
@Composable
fun reasoningPendingPortrait() = BoardPreview(
    Fixtures.snapshot(),
    reasoningTarget = au.edu.uts.vibepocket.control.Reasoning.Level.HIGH,
)

@PreviewTest
@Preview(name = "Short phone", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun shortPortrait() = BoardPreview(Fixtures.snapshot())

@PreviewTest
@Preview(name = "Large phone", widthDp = 600, heightDp = 960, showBackground = true)
@Composable
fun largePortrait() = BoardPreview(Fixtures.snapshot())

@PreviewTest
@Preview(name = "Landscape", widthDp = 873, heightDp = 393, showBackground = true)
@Composable
fun landscape() = BoardPreview(Fixtures.snapshot(), landscape = true)

@PreviewTest
@Preview(name = "Font 130", widthDp = 393, heightDp = 873, fontScale = 1.3f, showBackground = true)
@Composable
fun fontScale130() = BoardPreview(Fixtures.snapshot())

@PreviewTest
@Preview(name = "Font 200", widthDp = 393, heightDp = 873, fontScale = 2f, showBackground = true)
@Composable
fun fontScale200() = BoardPreview(Fixtures.snapshot())

@PreviewTest
@Preview(
    name = "Dark",
    widthDp = 393,
    heightDp = 873,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun darkPortrait() = BoardPreview(Fixtures.snapshot(), dark = true)

@PreviewTest
@Preview(name = "Chinese", widthDp = 393, heightDp = 873, locale = "zh-rCN", showBackground = true)
@Composable
fun chinesePortrait() = BoardPreview(Fixtures.snapshot())

@PreviewTest
@Preview(name = "RTL", widthDp = 393, heightDp = 873, locale = "ar-rXB", showBackground = true)
@Composable
fun rtlPortrait() = BoardPreview(Fixtures.snapshot())
