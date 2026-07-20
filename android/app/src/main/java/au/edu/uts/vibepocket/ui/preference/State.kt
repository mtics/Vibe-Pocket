package au.edu.uts.vibepocket.ui.preference

internal enum class Palette(val label: String) {
    LIGHT("Light"),
    SYSTEM("System"),
    DARK("Dark"),
}

internal enum class Hand(val label: String) {
    RIGHT("Right hand"),
    LEFT("Left hand"),
}

internal data class State(
    val palette: Palette = Palette.LIGHT,
    val hand: Hand = Hand.RIGHT,
)

internal fun palette(value: String?): Palette =
    Palette.entries.firstOrNull { it.name == value } ?: Palette.LIGHT

internal fun hand(value: String?): Hand =
    Hand.entries.firstOrNull { it.name == value } ?: Hand.RIGHT

internal fun Palette.usesDark(systemDark: Boolean): Boolean = when (this) {
    Palette.LIGHT -> false
    Palette.SYSTEM -> systemDark
    Palette.DARK -> true
}
