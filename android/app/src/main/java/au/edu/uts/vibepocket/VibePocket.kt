package au.edu.uts.vibepocket

import android.app.Application
import au.edu.uts.vibepocket.hardware.Hardware

class VibePocket : Application() {
    internal val hardware by lazy { Hardware(this) }
}
