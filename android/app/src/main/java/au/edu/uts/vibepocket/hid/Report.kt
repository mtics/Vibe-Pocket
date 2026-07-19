package au.edu.uts.vibepocket.hid

import au.edu.uts.vibepocket.profile.Action

internal data class Chord(
    val modifier: Int = 0,
    val usage: Int,
)

internal object Report {
    const val MODIFIER_LEFT_CONTROL = 0x01
    const val MODIFIER_LEFT_SHIFT = 0x02
    const val MODIFIER_LEFT_ALT = 0x04
    const val MODIFIER_LEFT_GUI = 0x08

    const val USAGE_A = 0x04
    const val USAGE_D = 0x07
    const val USAGE_J = 0x0D
    const val USAGE_M = 0x10
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

    fun encode(chord: Chord): ByteArray = byteArrayOf(
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

internal object Mapping {
    fun chords(action: Action): List<Chord>? = when (action.type) {
        "approve" -> listOf(Chord(usage = Report.USAGE_ENTER))
        "reject", "stop" -> listOf(Chord(usage = Report.USAGE_ESCAPE))
        "new_task" -> listOf(
            Chord(
                modifier = Report.MODIFIER_LEFT_GUI,
                usage = Report.USAGE_N,
            ),
        )
        "voice" -> listOf(
            Chord(
                modifier = Report.MODIFIER_LEFT_CONTROL or Report.MODIFIER_LEFT_SHIFT,
                usage = Report.USAGE_D,
            ),
        )
        "mode_cycle" -> listOf(
            Chord(
                modifier = SemanticModifiers,
                usage = Report.USAGE_P,
            ),
        )
        "model_picker" -> listOf(
            Chord(
                modifier = Report.MODIFIER_LEFT_CONTROL or Report.MODIFIER_LEFT_SHIFT,
                usage = Report.USAGE_M,
            ),
        )
        "reasoning_depth" -> when (action.delta) {
            1 -> listOf(Chord(modifier = SemanticModifiers, usage = Report.USAGE_U))
            -1 -> listOf(Chord(modifier = SemanticModifiers, usage = Report.USAGE_J))
            else -> null
        }
        "clear_input" -> listOf(
            Chord(
                modifier = Report.MODIFIER_LEFT_GUI,
                usage = Report.USAGE_A,
            ),
            Chord(usage = Report.USAGE_BACKSPACE),
        )
        "navigate" -> when (action.direction) {
            "up" -> listOf(Chord(usage = Report.USAGE_UP))
            "down" -> listOf(Chord(usage = Report.USAGE_DOWN))
            "left" -> listOf(Chord(usage = Report.USAGE_LEFT))
            "right" -> listOf(Chord(usage = Report.USAGE_RIGHT))
            else -> null
        }
        else -> null
    }

    private const val SemanticModifiers =
        Report.MODIFIER_LEFT_CONTROL or
            Report.MODIFIER_LEFT_SHIFT or
            Report.MODIFIER_LEFT_ALT
}
