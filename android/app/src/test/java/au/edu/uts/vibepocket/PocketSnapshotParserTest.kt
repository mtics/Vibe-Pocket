package au.edu.uts.vibepocket

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketSnapshotParserTest {
    @Test
    fun parsesCapabilityDrivenControllerSnapshot() {
        val snapshot = parsePocketSnapshot(JSONObject(CONTROLLER_SNAPSHOT))

        assertEquals("r_42", snapshot.revision)
        assertEquals(TaskState.WAITING, snapshot.controller?.taskState)
        assertEquals("Codex", snapshot.controller?.mode?.label)
        assertEquals("High", snapshot.controller?.reasoning?.label)
        assertEquals(2, snapshot.controller?.agents?.size)
        assertEquals("Turing", snapshot.controller?.agents?.first()?.label)
        assertEquals(6, snapshot.controller?.profile?.layers?.size)
        assertEquals(5, snapshot.controller?.profile?.inputs?.size)
        assertTrue(snapshot.inputEnabled("key_accept"))
        assertTrue(snapshot.inputEnabled("key_accept", ControllerGesture.DOUBLE_TAP))
        assertFalse(snapshot.inputEnabled("key_accept", ControllerGesture.HOLD))
        assertEquals("voice", snapshot.actionFor("key_accept", ControllerGesture.DOUBLE_TAP)?.type)
        assertTrue(snapshot.inputEnabled("joystick_up"))
        assertTrue(snapshot.inputEnabled("dial_cw"))
        assertFalse(snapshot.inputEnabled("key_stop"))
        assertEquals(3, snapshot.controller?.gestures?.size)
        assertTrue(snapshot.agentFocusEnabled(0))
        assertFalse(snapshot.agentFocusEnabled(2))
    }

    @Test
    fun selectedEmptyLayerDisablesMappedInputs() {
        val root = JSONObject(CONTROLLER_SNAPSHOT)
        root.getJSONObject("controller").put("activeLayerId", "layer-2")

        val snapshot = parsePocketSnapshot(root)

        assertFalse(snapshot.inputEnabled("key_accept"))
        assertFalse(snapshot.inputEnabled("joystick_up"))
        assertFalse(snapshot.inputEnabled("dial_cw"))
    }

    @Test
    fun v1DirectActionBindingStillResolvesAsTap() {
        val root = JSONObject(CONTROLLER_SNAPSHOT)
        val profile = root.getJSONObject("controller").getJSONObject("profile")
        profile.put("version", 1)
        profile.getJSONArray("layers").getJSONObject(0).getJSONObject("bindings")
            .put("key_accept", JSONObject().put("type", "approve"))

        val snapshot = parsePocketSnapshot(root)

        assertEquals("approve", snapshot.actionFor("key_accept", ControllerGesture.TAP)?.type)
        assertNull(snapshot.actionFor("key_accept", ControllerGesture.DOUBLE_TAP))
        assertTrue(snapshot.inputEnabled("key_accept", ControllerGesture.TAP))
    }

    @Test
    fun oldSnapshotKeepsOnlyVerifiedLegacyControls() {
        val snapshot = parsePocketSnapshot(
            JSONObject(
                """
                {
                  "revision":"r_old",
                  "status":{"state":"ready","message":null},
                  "controls":{"voice":true,"stop":false,"new-task":true,"approve":false,"reject":true}
                }
                """.trimIndent(),
            ),
        )

        assertNull(snapshot.controller)
        assertTrue(snapshot.inputEnabled("key_voice"))
        assertTrue(snapshot.inputEnabled("key_new_task"))
        assertTrue(snapshot.inputEnabled("key_reject"))
        assertTrue(snapshot.inputEnabled("key_attach"))
        assertFalse(snapshot.inputEnabled("key_mode"))
        assertFalse(snapshot.inputEnabled("joystick_up"))
        assertEquals(
            "voice",
            snapshot.commandForInput("key_voice", ControllerGesture.TAP).toJson().getString("kind"),
        )
    }

    @Test
    fun malformedOptionalControllerDataFallsBackConservatively() {
        val snapshot = parsePocketSnapshot(
            JSONObject(
                """
                {
                  "status":{"state":"ready"},
                  "controller":{
                    "profile":{"version":-2,"inputs":[],"layers":[]},
                    "taskState":"teleporting",
                    "focusedAgentIndex":99,
                    "agents":[{"label":"A","state":"unknown"},{"state":"complete"}],
                    "mode":{"available":"yes","label":null}
                  }
                }
                """.trimIndent(),
            ),
        )

        assertNull(snapshot.controller?.profile)
        assertEquals(TaskState.IDLE, snapshot.controller?.taskState)
        assertEquals(-1, snapshot.controller?.focusedAgentIndex)
        assertEquals(listOf(AgentStatus("A", TaskState.IDLE)), snapshot.controller?.agents)
        assertFalse(snapshot.controller?.mode?.available ?: true)
        assertFalse(snapshot.inputEnabled("key_mode"))
    }

    @Test
    fun serializesOnlyStructuredControllerCommands() {
        val binding = PocketCommand.Binding("key_voice", ControllerGesture.DOUBLE_TAP).toJson()
        val layer = PocketCommand.SelectLayer("layer-3").toJson()
        val focus = PocketCommand.FocusAgent(4).toJson()
        val update = PocketCommand.UpdateBinding(
            "layer-2",
            "key_voice",
            ControllerGesture.HOLD,
            ControllerAction("workflow", workflowId = "debug"),
        ).toJson()
        val clear = PocketCommand.ClearBinding(
            "layer-2",
            "key_voice",
            ControllerGesture.DOUBLE_TAP,
        ).toJson()
        val rename = PocketCommand.RenameLayer("layer-2", "Research").toJson()
        val reset = PocketCommand.ResetProfile.toJson()

        assertEquals("binding", binding.getString("kind"))
        assertEquals("key_voice", binding.getString("inputId"))
        assertEquals("double_tap", binding.getString("gesture"))
        assertEquals("select_layer", layer.getString("kind"))
        assertEquals("layer-3", layer.getString("layerId"))
        assertEquals("focus_agent", focus.getString("kind"))
        assertEquals(4, focus.getInt("index"))
        assertEquals("update_binding", update.getString("kind"))
        assertEquals("hold", update.getString("gesture"))
        assertEquals("debug", update.getJSONObject("action").getString("workflowId"))
        assertEquals(setOf("kind", "layerId", "inputId", "gesture"), clear.keys().asSequence().toSet())
        assertEquals("double_tap", clear.getString("gesture"))
        assertEquals("rename_layer", rename.getString("kind"))
        assertEquals("Research", rename.getString("name"))
        assertEquals(setOf("kind"), reset.keys().asSequence().toSet())
    }

    private companion object {
        val CONTROLLER_SNAPSHOT = """
            {
              "revision":"r_42",
              "status":{"state":"ready","message":"Current desktop task"},
              "controls":{
                "voice":true,"stop":false,"new-task":true,"approve":true,"reject":true,
                "clear-input":true,"focus-agent":true,"mode-cycle":true,"navigate":true,
                "reasoning":true,"workflow":true
              },
              "controller":{
                "activeLayerId":"layer-1",
                "taskState":"waiting",
                "focusedAgentIndex":0,
                "agents":[{"label":"Turing","state":"executing"},{"label":"Dalton","state":"complete"}],
                "mode":{"available":true,"label":"Codex"},
                "reasoning":{"available":true,"label":"High"},
                "gestures":[
                  {"id":"tap","label":"Tap"},{"id":"double_tap","label":"Double tap"},{"id":"hold","label":"Hold"}
                ],
                "actionCatalog":[
                  {"id":"approve","label":"Approve","action":{"type":"approve"}},
                  {"id":"voice","label":"Voice","action":{"type":"voice"}},
                  {"id":"focus_agent_1","label":"Focus agent 1","action":{"type":"focus_agent","index":0}},
                  {"id":"workflow_review-pr","label":"Review PR","action":{"type":"workflow","workflowId":"review-pr"}}
                ],
                "profile":{
                  "version":2,
                  "inputs":[
                    {"id":"key_accept","kind":"key","label":"Accept","icon":"check"},
                    {"id":"key_stop","kind":"key","label":"Stop","icon":"stop"},
                    {"id":"touch","kind":"touch","label":"Next agent","icon":"touch"},
                    {"id":"joystick_up","kind":"joystick","label":"Review PR","icon":"review"},
                    {"id":"dial_cw","kind":"dial","label":"More reasoning","icon":"dial"}
                  ],
                  "workflows":[{"id":"review-pr","label":"Review PR"}],
                  "layers":[
                    {"id":"layer-1","name":"Default","color":"#55D6A4","bindings":{
                      "key_accept":{"tap":{"type":"approve"},"double_tap":{"type":"voice"}},
                      "key_stop":{"tap":{"type":"stop"}},
                      "touch":{"tap":{"type":"focus_next"}},
                      "joystick_up":{"tap":{"type":"workflow","workflowId":"review-pr"}},
                      "dial_cw":{"tap":{"type":"reasoning_depth","delta":1}}
                    }},
                    {"id":"layer-2","name":"Layer 2","color":"#A020F0","bindings":{}},
                    {"id":"layer-3","name":"Layer 3","color":"#25D9E8","bindings":{}},
                    {"id":"layer-4","name":"Layer 4","color":"#FF8C24","bindings":{}},
                    {"id":"layer-5","name":"Layer 5","color":"#FF4F9A","bindings":{}},
                    {"id":"layer-6","name":"Layer 6","color":"#FFE04A","bindings":{}}
                  ]
                }
              }
            }
        """.trimIndent()
    }
}
