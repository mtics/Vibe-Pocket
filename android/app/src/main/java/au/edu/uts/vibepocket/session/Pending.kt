package au.edu.uts.vibepocket.session

import java.util.concurrent.ConcurrentHashMap

internal class Pending {
    private val ids = ConcurrentHashMap.newKeySet<String>()

    fun add(id: String): Boolean = ids.add(id)
    fun remove(id: String) = ids.remove(id)
    fun clear() = ids.clear()
    fun any(predicate: (String) -> Boolean): Boolean = ids.any(predicate)
    fun snapshot(): Set<String> = ids.toSet()
}
