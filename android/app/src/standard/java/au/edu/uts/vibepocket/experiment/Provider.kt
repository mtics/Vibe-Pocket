package au.edu.uts.vibepocket.experiment

import android.content.Context

internal object Provider : Capability {
    override val available = false
    override val description = ""
    override val permissionFailure = ""

    override fun permissions(sdk: Int): Array<String> = emptyArray()
    override fun start(context: Context) = Unit
    override fun failure(error: Throwable): String = error.message.orEmpty()
}
