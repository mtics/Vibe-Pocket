package au.edu.uts.vibepocket.hardware.micro.subscription

import android.content.Context

internal class Preferences(context: Context) : Store {
    private val values = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun read(clientId: String): Set<Channel> = values
        .getStringSet(clientId, emptySet())
        .orEmpty()
        .mapNotNull { value -> Channel.entries.find { it.name == value } }
        .toSet()

    override fun write(clientId: String, channels: Set<Channel>) {
        values.edit().putStringSet(clientId, channels.map(Channel::name).toSet()).apply()
    }

    override fun remove(clientId: String): Boolean = values.edit().remove(clientId).commit()

    private companion object {
        const val name = "vibe-pocket-micro-subscriptions"
    }
}
