package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input

internal data class Control(
    val input: Input,
    val gesture: Gesture.Kind,
    val action: Action,
)

internal class Catalog private constructor(
    private val controls: Map<Key, Control>,
) {
    fun find(
        type: String,
        direction: String? = null,
        delta: Int? = null,
        workflowId: String? = null,
    ): Control? = controls[Key(type, direction, delta, null, workflowId, null)]

    companion object {
        fun from(snapshot: Snapshot): Catalog {
            val desktop = snapshot.desktop
            val profile = desktop?.profile
            val layer = snapshot.activeLayer
            if (profile == null || layer == null) return Catalog(emptyMap())
            val inputs = profile.inputs.associateBy(Input::id)
            val controls = linkedMapOf<Key, Control>()
            profile.inputs.forEach { input ->
                val binding = layer.bindings[input.id] ?: return@forEach
                Gesture.Kind.entries.forEach { gesture ->
                    val action = binding.actions[gesture] ?: return@forEach
                    controls.putIfAbsent(Key.from(action), Control(inputs.getValue(input.id), gesture, action))
                }
            }
            return Catalog(controls)
        }
    }

    private data class Key(
        val type: String,
        val direction: String?,
        val delta: Int?,
        val index: Int?,
        val workflowId: String?,
        val layerId: String?,
    ) {
        companion object {
            fun from(action: Action) = Key(
                action.type,
                action.direction,
                action.delta,
                action.index,
                action.workflowId,
                action.layerId,
            )
        }
    }
}
