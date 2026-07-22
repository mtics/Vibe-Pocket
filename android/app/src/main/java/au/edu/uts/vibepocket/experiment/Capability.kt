package au.edu.uts.vibepocket.experiment

import android.content.Context

internal interface Capability {
    val available: Boolean
    val description: String
    val permissionFailure: String

    fun permissions(sdk: Int): Array<String>
    fun start(context: Context)
    fun failure(error: Throwable): String
}
