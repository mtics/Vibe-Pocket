package au.edu.uts.vibepocket.hardware.micro

internal object SignalAction {
    const val act06 = "au.edu.uts.vibepocket.micro.ACT06"
    const val act07 = "au.edu.uts.vibepocket.micro.ACT07"
    const val act08 = "au.edu.uts.vibepocket.micro.ACT08"
    const val act09 = "au.edu.uts.vibepocket.micro.ACT09"
    const val act10 = "au.edu.uts.vibepocket.micro.ACT10"
    const val act12 = "au.edu.uts.vibepocket.micro.ACT12"

    private val keys = mapOf(
        act06 to "ACT06",
        act07 to "ACT07",
        act08 to "ACT08",
        act09 to "ACT09",
        act10 to "ACT10",
        act12 to "ACT12",
    )

    fun key(action: String?): String? = keys[action]
}
