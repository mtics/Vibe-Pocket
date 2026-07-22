package au.edu.uts.vibepocket.hardware.micro.protocol

import au.edu.uts.vibepocket.hardware.micro.BatteryState
import org.json.JSONObject

data class Reply(
    val json: String,
    val method: String,
    val recognized: Boolean,
)

class Rpc(
    private val battery: () -> BatteryState = { BatteryState.unknown },
) {
    fun reply(json: String): Reply {
        val request = JSONObject(json)
        val method = request.optString("method")
        val id = request.opt("id") ?: JSONObject.NULL
        val result = when (method) {
            "sys.version" -> JSONObject().put("version", version)
            "device.status" -> battery().let { state ->
                JSONObject()
                    .put("version", version)
                    .put("profile_index", 0)
                    .put("layer_index", 1)
                    .put("battery", state.level.coerceIn(0, 100))
                    .put("is_charging", state.charging)
            }
            "v.oai.thstatus",
            "v.oai.rgbcfg",
            "lights.preview",
            "host.focused_app",
            -> JSONObject().put("ok", true)
            else -> null
        }
        val response = JSONObject().put("id", id)
        if (result != null) {
            response.put("result", result)
        } else {
            response.put(
                "error",
                JSONObject().put("code", -32601).put("message", "Method not found"),
            )
        }
        return Reply(response.toString(), method, result != null)
    }

    companion object {
        const val version = "0.1.0-vibe-pocket"
    }
}
