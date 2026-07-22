package au.edu.uts.vibepocket.hardware.micro

internal enum class HidControl {
    SUSPEND,
    EXIT_SUSPEND,
}

internal fun hidControl(bytes: ByteArray): HidControl? = when {
    bytes.contentEquals(byteArrayOf(0x00)) -> HidControl.SUSPEND
    bytes.contentEquals(byteArrayOf(0x01)) -> HidControl.EXIT_SUSPEND
    else -> null
}
