package au.edu.uts.vibepocket.hardware.micro.protocol

import au.edu.uts.vibepocket.hardware.micro.BatteryState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcTest {
    @Test
    fun knownRequestsEchoIdsAndReportStatus() {
        val reply = Rpc(battery = { BatteryState(83, charging = true) }).reply(
            "{\"method\":\"device.status\",\"id\":\"request-7\"}",
        )
        val json = JSONObject(reply.json)

        assertTrue(reply.recognized)
        assertEquals("request-7", json.getString("id"))
        assertEquals(83, json.getJSONObject("result").getInt("battery"))
        assertTrue(json.getJSONObject("result").getBoolean("is_charging"))
    }

    @Test
    fun unknownRequestsReturnMethodNotFound() {
        val reply = Rpc().reply("{\"method\":\"unknown\",\"id\":9}")
        val json = JSONObject(reply.json)

        assertFalse(reply.recognized)
        assertEquals(9, json.getInt("id"))
        assertEquals(-32601, json.getJSONObject("error").getInt("code"))
    }

    @Test
    fun compatibilityRequestsRemainRecognizedAndEchoTheirIds() {
        val methods = listOf(
            "sys.version",
            "v.oai.thstatus",
            "v.oai.rgbcfg",
            "lights.preview",
            "host.focused_app",
        )

        methods.forEachIndexed { index, method ->
            val reply = Rpc().reply(JSONObject().put("method", method).put("id", index).toString())
            val json = JSONObject(reply.json)

            assertTrue(method, reply.recognized)
            assertEquals(method, index, json.getInt("id"))
            assertTrue(method, json.has("result"))
        }
    }

    @Test
    fun signalsKeepPhysicalKeysSeparateFromBusinessMeaning() {
        val press = JSONObject(Signal.Key("ACT06", Act.PRESS).json())
        val release = JSONObject(Signal.Key("ACT06", Act.RELEASE).json())
        val step = JSONObject(Signal.Key("ENC_CC", Act.STEP).json())

        assertEquals(1, press.getJSONObject("params").getInt("act"))
        assertEquals(0, release.getJSONObject("params").getInt("act"))
        assertEquals("ENC_CC", step.getJSONObject("params").getString("k"))
        assertEquals(2, step.getJSONObject("params").getInt("act"))
    }
}
