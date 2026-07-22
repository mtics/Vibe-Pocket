package au.edu.uts.vibepocket.hardware.hid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import au.edu.uts.vibepocket.VibePocket
import au.edu.uts.vibepocket.profile.Action

class Control : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actions = when (intent.action) {
            reasoningAction -> reasoning(intent)
            modelAction -> model(intent)
            else -> null
        }
        if (actions.isNullOrEmpty()) {
            Log.e(tag, "event=probe_rejected action=${intent.action}")
            return
        }

        val pending = goAsync()
        val keyboard = (context.applicationContext as VibePocket).hardware.keyboard
        val accepted = keyboard.send(actions, interval(intent.action, intent)) { result ->
            Log.i(tag, "event=probe_completed action=${intent.action} result=$result")
            pending.finish()
        }
        if (!accepted) {
            Log.e(tag, "event=probe_rejected action=${intent.action} reason=transport")
            pending.finish()
        }
    }

    private fun reasoning(intent: Intent): List<Action>? {
        val delta = intent.getIntExtra("delta", 0).takeIf { it == -1 || it == 1 } ?: return null
        val steps = intent.steps()?.takeIf { it > 0 } ?: return null
        return List(steps) { Action("reasoning_depth", delta = delta) }
    }

    private fun model(intent: Intent): List<Action>? {
        val steps = intent.steps() ?: return null
        val direction = intent.getStringExtra("direction").takeIf { it == "up" || it == "down" }
        if (steps > 0 && direction == null) return null
        return buildList {
            if (intent.getBooleanExtra("open", true)) add(Action("model_picker"))
            repeat(steps) { add(Action("navigate", direction = direction)) }
            if (intent.getBooleanExtra("confirm", true)) add(Action("approve"))
        }.takeIf { it.isNotEmpty() }
    }

    private fun Intent.steps(): Int? = getIntExtra("steps", 0).takeIf { it in 0..6 }

    private fun interval(action: String?, intent: Intent? = null): Long {
        val fallback = if (action == modelAction) 240 else 45
        return intent?.getIntExtra("interval", fallback)?.toLong()?.coerceIn(40L, 1_000L) ?: fallback.toLong()
    }

    companion object {
        private const val tag = "VibePocketHid"
        private const val reasoningAction = "au.edu.uts.vibepocket.hid.REASONING"
        private const val modelAction = "au.edu.uts.vibepocket.hid.MODEL"
    }
}
