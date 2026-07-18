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
        assertEquals("agent-0123456789abcdef01234567", snapshot.controller?.focusedAgentId)
        assertEquals(0, snapshot.controller?.focusedAgentIndex)
        assertEquals(VoiceStatus(available = true, active = false), snapshot.controller?.voice)
        assertEquals("Codex", snapshot.controller?.mode?.label)
        assertEquals("Workspace", snapshot.controller?.access?.label)
        assertEquals("High", snapshot.controller?.reasoning?.label)
        assertEquals("Scope", snapshot.controller?.userInput?.header)
        assertEquals(2, snapshot.controller?.userInput?.options?.size)
        assertEquals("Broad", snapshot.controller?.userInput?.options?.get(1)?.label)
        assertEquals(1, snapshot.controller?.userInput?.selectedOptionIndex)
        assertEquals(2, snapshot.controller?.agents?.size)
        assertEquals("Turing", snapshot.controller?.agents?.first()?.label)
        assertEquals("agent-0123456789abcdef01234567", snapshot.controller?.agents?.first()?.id)
        assertFalse(snapshot.controller?.agents?.first()?.focused == true)
        assertEquals(TaskState.THINKING, snapshot.controller?.agents?.first()?.state)
        assertEquals(TaskState.UNREAD, snapshot.controller?.agents?.last()?.state)
        assertEquals(6, snapshot.controller?.profile?.layers?.size)
        assertEquals(5, snapshot.controller?.profile?.inputs?.size)
        assertEquals("Review this change.", snapshot.controller?.profile?.workflows?.first()?.prompt)
        assertTrue(snapshot.controller?.actionCatalog?.any { it.action.type == "select_layer" && it.action.layerId == "layer-2" } == true)
        assertTrue(snapshot.inputEnabled("key_accept"))
        assertTrue(snapshot.inputEnabled("key_accept", ControllerGesture.DOUBLE_TAP))
        assertFalse(snapshot.inputEnabled("key_accept", ControllerGesture.HOLD))
        assertEquals("voice", snapshot.actionFor("key_accept", ControllerGesture.DOUBLE_TAP)?.type)
        assertTrue(snapshot.inputEnabled("joystick_up"))
        assertTrue(snapshot.inputEnabled("dial_cw"))
        assertFalse(snapshot.inputEnabled("key_stop"))
        assertEquals(3, snapshot.controller?.gestures?.size)
        assertTrue(snapshot.agentFocusEnabled("agent-0123456789abcdef01234567"))
        assertFalse(snapshot.agentFocusEnabled("agent-ffffffffffffffffffffffff"))
    }

    @Test
    fun emptyAgentSlotsAreDisabledUnfocusedPlaceholders() {
        val snapshot = parsePocketSnapshot(JSONObject(CONTROLLER_SNAPSHOT))

        val slots = snapshot.agentSlots()

        assertEquals(6, slots.size)
        assertTrue(slots[0].canFocus)
        assertTrue(slots[0].focused)
        assertFalse(slots[1].focused)
        assertNull(slots[2].agent)
        assertFalse(slots[2].canFocus)
        assertFalse(slots[2].focused)
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
                    "focusedAgentId":"agent-bbbbbbbbbbbbbbbbbbbbbbbb",
                    "agents":[
                      {"id":"agent-aaaaaaaaaaaaaaaaaaaaaaaa","label":"A","state":"unknown","focused":false},
                      {"id":"agent-not-hex","label":"Invalid ID","state":"complete"}
                    ],
                    "mode":{"available":"yes","label":null}
                  }
                }
                """.trimIndent(),
            ),
        )

        assertNull(snapshot.controller?.profile)
        assertEquals(TaskState.IDLE, snapshot.controller?.taskState)
        assertEquals(-1, snapshot.controller?.focusedAgentIndex)
        assertNull(snapshot.controller?.focusedAgentId)
        assertEquals(
            listOf(AgentStatus("agent-aaaaaaaaaaaaaaaaaaaaaaaa", "A", TaskState.IDLE, false)),
            snapshot.controller?.agents,
        )
        assertFalse(snapshot.controller?.mode?.available ?: true)
        assertFalse(snapshot.inputEnabled("key_mode"))
    }

    @Test
    fun serializesOnlyStructuredControllerCommands() {
        val binding = PocketCommand.Binding("key_voice", ControllerGesture.DOUBLE_TAP).toJson()
        val layer = PocketCommand.SelectLayer("layer-3").toJson()
        val focus = PocketCommand.FocusAgent("agent-444444444444444444444444").toJson()
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
        val color = PocketCommand.UpdateLayerColor("layer-2", "#55D6A4").toJson()
        val workflow = PocketCommand.UpdateWorkflowPrompt("debug", "Investigate from evidence.").toJson()
        val reset = PocketCommand.ResetProfile.toJson()

        assertEquals("binding", binding.getString("kind"))
        assertEquals("key_voice", binding.getString("inputId"))
        assertEquals("double_tap", binding.getString("gesture"))
        assertEquals("select_layer", layer.getString("kind"))
        assertEquals("layer-3", layer.getString("layerId"))
        assertEquals("focus_agent", focus.getString("kind"))
        assertEquals("agent-444444444444444444444444", focus.getString("agentId"))
        assertFalse(focus.has("index"))
        assertEquals("update_binding", update.getString("kind"))
        assertEquals("hold", update.getString("gesture"))
        assertEquals("debug", update.getJSONObject("action").getString("workflowId"))
        assertEquals(setOf("kind", "layerId", "inputId", "gesture"), clear.keys().asSequence().toSet())
        assertEquals("double_tap", clear.getString("gesture"))
        assertEquals("rename_layer", rename.getString("kind"))
        assertEquals("Research", rename.getString("name"))
        assertEquals("update_layer_color", color.getString("kind"))
        assertEquals("#55D6A4", color.getString("color"))
        assertEquals("update_workflow", workflow.getString("kind"))
        assertEquals("Investigate from evidence.", workflow.getString("prompt"))
        assertEquals(setOf("kind"), reset.keys().asSequence().toSet())
        assertEquals("voice_start", PocketCommand.VoiceStart.toJson().getString("kind"))
        assertEquals("voice_stop", PocketCommand.VoiceStop.toJson().getString("kind"))
        assertEquals(
            "Draft from phone",
            PocketCommand.DictationResult("Draft from phone").toJson().getString("text"),
        )
        assertEquals(
            "dictation_result",
            PocketCommand.DictationResult("Draft from phone").toJson().getString("kind"),
        )
    }

    private companion object {
        val CONTROLLER_SNAPSHOT = """
            {
              "revision":"r_42",
              "status":{"state":"ready","message":"Current desktop task"},
              "controls":{
                "voice":true,"stop":false,"new-task":true,"approve":true,"reject":true,
                "clear-input":true,"focus-agent":true,"mode-cycle":true,"navigate":true,
                "access-cycle":true,"reasoning":true,"workflow":true
              },
              "controller":{
                "activeLayerId":"layer-1",
                "taskState":"waiting",
                "focusedAgentIndex":0,
                "focusedAgentId":"agent-0123456789abcdef01234567",
                "voice":{"available":true,"active":false},
                "agents":[
                  {"id":"agent-0123456789abcdef01234567","label":"Turing","state":"thinking","focused":false},
                  {"id":"agent-89abcdef0123456789abcdef","label":"Dalton","state":"unread","focused":false}
                ],
                "mode":{"available":true,"label":"Codex"},
                "access":{"available":true,"label":"Workspace"},
                "reasoning":{"available":true,"label":"High"},
                "userInput":{
                  "questionIndex":0,"questionCount":1,"header":"Scope",
                  "question":"Which scope should Codex use?",
                  "options":[
                    {"label":"Focused","description":"Only the affected module."},
                    {"label":"Broad","description":"Include adjacent cleanup."}
                  ],
                  "selectedOptionIndex":1,"hasSpokenAnswer":false,"isSecret":false
                },
                "gestures":[
                  {"id":"tap","label":"Tap"},{"id":"double_tap","label":"Double tap"},{"id":"hold","label":"Hold"}
                ],
                "actionCatalog":[
                  {"id":"approve","label":"Approve","action":{"type":"approve"}},
                  {"id":"voice","label":"Voice","action":{"type":"voice"}},
                  {"id":"access_cycle","label":"Next access level","action":{"type":"access_cycle"}},
                  {"id":"focus_agent_1","label":"Focus agent 1","action":{"type":"focus_agent","index":0}},
                  {"id":"select_layer_2","label":"Select layer 2","action":{"type":"select_layer","layerId":"layer-2"}},
                  {"id":"workflow_review-pr","label":"Review PR","action":{"type":"workflow","workflowId":"review-pr"}}
                ],
                "profile":{
                  "version":3,
                  "inputs":[
                    {"id":"key_accept","kind":"key","label":"Accept","icon":"check"},
                    {"id":"key_stop","kind":"key","label":"Stop","icon":"stop"},
                    {"id":"touch","kind":"touch","label":"Next agent","icon":"touch"},
                    {"id":"joystick_up","kind":"joystick","label":"Review PR","icon":"review"},
                    {"id":"dial_cw","kind":"dial","label":"More reasoning","icon":"dial"}
                  ],
                  "workflows":[{"id":"review-pr","label":"Review PR","prompt":"Review this change."}],
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
