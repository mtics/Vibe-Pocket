package au.edu.uts.vibepocket.hardware

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class Restore : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val posted = Recovery.post(context, intent.getIntExtra(Recovery.ownerPid, 0))
        setResultCode(if (posted) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        Log.i(tag, "Classic hardware recovery action posted=$posted")
    }

    companion object {
        private const val tag = "VibePocketHardware"
    }
}
