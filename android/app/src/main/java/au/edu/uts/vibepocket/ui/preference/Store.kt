package au.edu.uts.vibepocket.ui.preference

import android.content.Context

internal class Store(context: Context) {
    private val preferences = context.getSharedPreferences("display", Context.MODE_PRIVATE)

    fun read(): State = State(
        palette = palette(preferences.getString("palette", null)),
        hand = hand(preferences.getString("hand", null)),
    )

    fun write(state: State): Boolean =
        preferences.edit()
            .putString("palette", state.palette.name)
            .putString("hand", state.hand.name)
            .commit()
}
