package au.edu.uts.vibepocket.hardware

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import au.edu.uts.vibepocket.MainActivity

class Return : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val settle = object : Runnable {
        override fun run() {
            if (!ownerAlive()) {
                startActivity(
                    Intent(this@Return, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                )
                finish()
            } else if (SystemClock.elapsedRealtime() < deadline) {
                handler.postDelayed(this, pollMs)
            } else {
                Recovery.post(this@Return, ownerPid)
                finish()
            }
        }
    }
    private var ownerPid = 0
    private var deadline = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ownerPid = intent.getIntExtra(Recovery.ownerPid, 0)
        deadline = SystemClock.elapsedRealtime() + timeoutMs
        handler.post(settle)
    }

    override fun onDestroy() {
        handler.removeCallbacks(settle)
        super.onDestroy()
    }

    private fun ownerAlive(): Boolean {
        if (ownerPid <= 0) return false
        return getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().any {
            it.pid == ownerPid && it.processName == packageName
        }
    }

    companion object {
        private const val pollMs = 25L
        private const val timeoutMs = 5_000L
    }
}
