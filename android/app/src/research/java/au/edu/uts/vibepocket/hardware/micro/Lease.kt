package au.edu.uts.vibepocket.hardware.micro

enum class Restoration {
    NOTHING,
    CLEAR,
    REQUEST,
    RETAIN,
}

fun restoration(active: Boolean, original: String?, current: String?): Restoration = when {
    !active -> Restoration.NOTHING
    original.isNullOrBlank() -> Restoration.RETAIN
    current == original -> Restoration.CLEAR
    current == Name.advertised -> Restoration.REQUEST
    else -> Restoration.RETAIN
}
