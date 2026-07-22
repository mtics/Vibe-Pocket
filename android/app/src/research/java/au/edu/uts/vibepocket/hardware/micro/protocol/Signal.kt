package au.edu.uts.vibepocket.hardware.micro.protocol

import org.json.JSONObject

enum class Act(val wire: Int) {
    RELEASE(0),
    PRESS(1),
    STEP(2),
}

sealed interface Signal {
    fun json(): String

    data class Key(
        val key: String,
        val act: Act,
        val agent: Int? = null,
    ) : Signal {
        override fun json(): String = JSONObject()
            .put("method", "v.oai.hid")
            .put(
                "params",
                JSONObject().put("k", key).put("act", act.wire).also { params ->
                    agent?.let { params.put("ag", it) }
                },
            )
            .toString()
    }

    data class Radial(
        val angle: Double,
        val distance: Double,
    ) : Signal {
        override fun json(): String = JSONObject()
            .put("method", "v.oai.rad")
            .put("params", JSONObject().put("a", angle).put("d", distance))
            .toString()
    }
}
