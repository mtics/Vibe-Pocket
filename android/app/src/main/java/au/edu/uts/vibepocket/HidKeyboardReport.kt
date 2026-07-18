package au.edu.uts.vibepocket

internal data class HidChord(
    val modifier: Int = 0,
    val usage: Int,
)

internal object HidKeyboardReport {
    const val MODIFIER_LEFT_CONTROL = 0x01
    const val MODIFIER_LEFT_SHIFT = 0x02
    const val MODIFIER_LEFT_ALT = 0x04
    const val MODIFIER_LEFT_GUI = 0x08

    const val USAGE_A = 0x04
    const val USAGE_D = 0x07
    const val USAGE_J = 0x0D
    const val USAGE_N = 0x11
    const val USAGE_P = 0x13
    const val USAGE_U = 0x18
    const val USAGE_ENTER = 0x28
    const val USAGE_ESCAPE = 0x29
    const val USAGE_BACKSPACE = 0x2A
    const val USAGE_TAB = 0x2B
    const val USAGE_RIGHT = 0x4F
    const val USAGE_LEFT = 0x50
    const val USAGE_DOWN = 0x51
    const val USAGE_UP = 0x52

    fun encode(chord: HidChord): ByteArray = byteArrayOf(
        chord.modifier.toByte(),
        0,
        chord.usage.toByte(),
        0,
        0,
        0,
        0,
        0,
    )

    val release: ByteArray = ByteArray(8)
}

internal object CodexHidMapping {
    fun actionFor(
        snapshot: PocketSnapshot,
        inputId: String,
        gesture: ControllerGesture,
    ): ControllerAction? = snapshot.actionFor(inputId, gesture)
        ?: if (gesture == ControllerGesture.TAP && snapshot.controller?.profile == null) {
            when (inputId) {
                "key_accept" -> ControllerAction("approve")
                "key_reject" -> ControllerAction("reject")
                "key_voice" -> ControllerAction("voice")
                "key_stop" -> ControllerAction("stop")
                "key_mode" -> ControllerAction("mode_cycle")
                "key_clear" -> ControllerAction("clear_input")
                "key_up" -> ControllerAction("navigate", direction = "up")
                "key_down" -> ControllerAction("navigate", direction = "down")
                "key_left" -> ControllerAction("navigate", direction = "left")
                "key_right" -> ControllerAction("navigate", direction = "right")
                else -> null
            }
        } else {
            null
        }

    fun chords(action: ControllerAction?): List<HidChord>? = when (action?.type) {
        "approve" -> listOf(HidChord(usage = HidKeyboardReport.USAGE_ENTER))
        "reject", "stop" -> listOf(HidChord(usage = HidKeyboardReport.USAGE_ESCAPE))
        "new_task" -> listOf(
            HidChord(
                modifier = HidKeyboardReport.MODIFIER_LEFT_GUI,
                usage = HidKeyboardReport.USAGE_N,
            ),
        )
        "voice" -> listOf(
            HidChord(
                modifier = HidKeyboardReport.MODIFIER_LEFT_CONTROL or HidKeyboardReport.MODIFIER_LEFT_SHIFT,
                usage = HidKeyboardReport.USAGE_D,
            ),
        )
        "mode_cycle" -> listOf(
            HidChord(
                modifier = semanticCommandModifiers,
                usage = HidKeyboardReport.USAGE_P,
            ),
        )
        "reasoning_depth" -> when (action.delta) {
            1 -> listOf(HidChord(modifier = semanticCommandModifiers, usage = HidKeyboardReport.USAGE_U))
            -1 -> listOf(HidChord(modifier = semanticCommandModifiers, usage = HidKeyboardReport.USAGE_J))
            else -> null
        }
        "clear_input" -> listOf(
            HidChord(
                modifier = HidKeyboardReport.MODIFIER_LEFT_GUI,
                usage = HidKeyboardReport.USAGE_A,
            ),
            HidChord(usage = HidKeyboardReport.USAGE_BACKSPACE),
        )
        "navigate" -> when (action.direction) {
            "up" -> listOf(HidChord(usage = HidKeyboardReport.USAGE_UP))
            "down" -> listOf(HidChord(usage = HidKeyboardReport.USAGE_DOWN))
            "left" -> listOf(HidChord(usage = HidKeyboardReport.USAGE_LEFT))
            "right" -> listOf(HidChord(usage = HidKeyboardReport.USAGE_RIGHT))
            else -> null
        }
        else -> null
    }

    fun shouldUseHid(action: ControllerAction?, hasUserInput: Boolean): Boolean {
        return action?.type == "navigate" && chords(action) != null && !hasUserInput
    }

    private const val semanticCommandModifiers =
        HidKeyboardReport.MODIFIER_LEFT_CONTROL or
            HidKeyboardReport.MODIFIER_LEFT_SHIFT or
            HidKeyboardReport.MODIFIER_LEFT_ALT
}
