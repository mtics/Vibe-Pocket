package au.edu.uts.vibepocket.bridge

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Agent
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.Desktop
import au.edu.uts.vibepocket.control.MaxAgents
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Tasks
import au.edu.uts.vibepocket.control.TargetRef
import au.edu.uts.vibepocket.control.Voice
import au.edu.uts.vibepocket.control.agentSlots
import au.edu.uts.vibepocket.control.commandFor
import au.edu.uts.vibepocket.control.encode
import au.edu.uts.vibepocket.control.decodeCommand
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Gesture
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    @Test
    fun rejectsSnapshotWithoutProtocolVersion() {
        val failure = assertThrows(Failure::class.java) {
            decode(JSONObject().put("revision", "r_missing"))
        }

        assertEquals(
            "The Vibe Pocket bridge returned an incompatible snapshot protocol version.",
            failure.message,
        )
    }

    @Test
    fun rejectsSnapshotUnlessProtocolVersionIsTheExactCurrentInteger() {
        listOf<Any>(ProtocolVersion - 1, ProtocolVersion + 1, ProtocolVersion.toString(), ProtocolVersion.toDouble())
            .forEach { version ->
                val failure = assertThrows(Failure::class.java) {
                    decode(JSONObject().put("protocolVersion", version).put("revision", "r_wrong"))
                }

                assertEquals(
                    "The Vibe Pocket bridge returned an incompatible snapshot protocol version.",
                    failure.message,
                )
            }
    }

    @Test
    fun parsesCapabilityDrivenControllerSnapshot() {
        val snapshot = decode(JSONObject(CONTROLLER_SNAPSHOT))

        assertEquals("r_42", snapshot.revision)
        assertTrue(snapshot.transportFresh)
        assertEquals(1_700_000_000_000L, snapshot.observedAtMillis)
        assertEquals(Activity.WAITING, snapshot.desktop?.activity)
        assertTrue(snapshot.desktop?.foreground == true)
        assertEquals("agent-0123456789abcdef01234567", snapshot.desktop?.focusedAgentId)
        assertEquals(Desktop.Binding.State.CONFIRMED, snapshot.desktop?.binding?.state)
        assertEquals("agent-0123456789abcdef01234567", snapshot.desktop?.binding?.contextId)
        assertEquals(Target, snapshot.desktop?.binding?.target?.boundRef)
        assertTrue(snapshot.sources.appServer.fresh)
        assertFalse(snapshot.sources.desktopUI.fresh)
        assertEquals(0, snapshot.desktop?.focusedAgentIndex)
        assertEquals(Voice(available = true, active = false), snapshot.desktop?.voice)
        assertEquals("Codex", snapshot.desktop?.mode?.label)
        assertEquals("Workspace", snapshot.desktop?.access?.label)
        assertEquals("High", snapshot.desktop?.reasoning?.label)
        assertEquals(Reasoning.Level.HIGH, snapshot.desktop?.reasoning?.level)
        assertTrue(snapshot.desktop?.reasoning?.canIncrease == true)
        assertTrue(snapshot.desktop?.reasoning?.canDecrease == true)
        assertEquals(Reasoning.Level.XHIGH, snapshot.desktop?.reasoning?.increaseTo)
        assertEquals(Reasoning.Level.MEDIUM, snapshot.desktop?.reasoning?.decreaseTo)
        assertTrue(snapshot.capabilities.modelPicker)
        assertTrue(snapshot.capabilities.model)
        assertEquals("gpt-test", snapshot.desktop?.model?.id)
        assertEquals(2, snapshot.desktop?.model?.options?.size)
        assertEquals("Scope", snapshot.desktop?.question?.header)
        assertEquals(2, snapshot.desktop?.question?.options?.size)
        assertEquals("Broad", snapshot.desktop?.question?.options?.get(1)?.label)
        assertEquals(1, snapshot.desktop?.question?.selectedOptionIndex)
        assertEquals(2, snapshot.desktop?.agents?.size)
        assertEquals("Turing", snapshot.desktop?.agents?.first()?.label)
        assertEquals("agent-0123456789abcdef01234567", snapshot.desktop?.agents?.first()?.id)
        assertFalse(snapshot.desktop?.agents?.first()?.focused == true)
        assertEquals(Activity.THINKING, snapshot.desktop?.agents?.first()?.activity)
        assertEquals(Activity.UNREAD, snapshot.desktop?.agents?.last()?.activity)
        assertEquals(Agent.Freshness.FRESH, snapshot.desktop?.agents?.first()?.freshness)
        assertTrue(snapshot.desktop?.agents?.first()?.actionable == true)
        assertEquals(Tasks.Availability.FRESH, snapshot.desktop?.tasks?.availability)
        assertEquals(6, snapshot.desktop?.profile?.layers?.size)
        assertEquals(5, snapshot.desktop?.profile?.inputs?.size)
        assertEquals("Review this change.", snapshot.desktop?.profile?.workflows?.first()?.prompt)
        assertTrue(snapshot.desktop?.choices?.any { it.action.type == "select_layer" && it.action.layerId == "layer-2" } == true)
        assertTrue(snapshot.inputEnabled("key_accept"))
        assertTrue(snapshot.inputEnabled("key_accept", Gesture.Kind.DOUBLE_TAP))
        assertFalse(snapshot.inputEnabled("key_accept", Gesture.Kind.HOLD))
        assertEquals("voice", snapshot.actionFor("key_accept", Gesture.Kind.DOUBLE_TAP)?.type)
        assertTrue(snapshot.inputEnabled("joystick_up"))
        assertTrue(snapshot.inputEnabled("dial_cw"))
        assertFalse(snapshot.inputEnabled("key_stop"))
        assertEquals(3, snapshot.desktop?.gestures?.size)
        assertTrue(snapshot.agentFocusEnabled("agent-0123456789abcdef01234567"))
        assertFalse(snapshot.agentFocusEnabled("agent-ffffffffffffffffffffffff"))
    }

    @Test
    fun staleTaskCatalogRemainsVisibleButCannotFocusAgents() {
        val root = JSONObject(CONTROLLER_SNAPSHOT)
        root.getJSONObject("controller")
            .put("tasks", JSONObject().put("availability", "stale").put("message", "Catalog offline"))
            .getJSONArray("agents")
            .getJSONObject(0)
            .put("freshness", "stale")
            .put("actionable", false)

        val snapshot = decode(root)

        assertEquals(Tasks.Availability.STALE, snapshot.desktop?.tasks?.availability)
        assertEquals("Catalog offline", snapshot.desktop?.tasks?.message)
        assertEquals(Agent.Freshness.STALE, snapshot.desktop?.agents?.first()?.freshness)
        assertFalse(snapshot.agentFocusEnabled("agent-0123456789abcdef01234567"))
    }

    @Test
    fun missingOrInvalidObservationIsNeverTreatedAsFresh() {
        val missing = JSONObject(CONTROLLER_SNAPSHOT).apply { remove("observation") }
        val invalid = JSONObject(CONTROLLER_SNAPSHOT).put(
            "observation",
            JSONObject().put("fresh", true).put("observedAt", "now"),
        )

        assertFalse(decode(missing).transportFresh)
        assertNull(decode(missing).observedAtMillis)
        assertFalse(decode(invalid).transportFresh)
        assertNull(decode(invalid).observedAtMillis)
    }

    @Test
    fun retainedObservationKeepsItsTimestampButBecomesStale() {
        val root = JSONObject(CONTROLLER_SNAPSHOT)
        root.getJSONObject("observation").put("fresh", false)

        val snapshot = decode(root)

        assertFalse(snapshot.transportFresh)
        assertEquals(1_700_000_000_000L, snapshot.observedAtMillis)
    }

    @Test
    fun confirmedBindingMustMatchTheFocusedAgentIdentity() {
        val root = JSONObject(CONTROLLER_SNAPSHOT)
        root.getJSONObject("controller").getJSONObject("binding").getJSONObject("visible")
            .put("contextId", "agent-89abcdef0123456789abcdef")

        val snapshot = decode(root)

        assertEquals(Desktop.Binding.State.CONFLICT, snapshot.desktop?.binding?.state)
    }

    @Test
    fun missingBindingEvidenceNeverDefaultsToConfirmed() {
        val root = JSONObject(CONTROLLER_SNAPSHOT)
        root.getJSONObject("controller").remove("binding")

        val snapshot = decode(root)

        assertEquals(Desktop.Binding.State.RECONCILING, snapshot.desktop?.binding?.state)
        assertEquals(null, snapshot.desktop?.binding?.contextId)
    }

    @Test
    fun agentSlotsContainOnlyRealTasksAndPreserveFocusedIdentity() {
        val snapshot = decode(JSONObject(CONTROLLER_SNAPSHOT))

        val slots = snapshot.agentSlots()

        assertEquals(2, slots.size)
        assertTrue(slots[0].canFocus)
        assertTrue(slots[0].focused)
        assertFalse(slots[1].focused)
        assertTrue(slots.all { it.agent != null })
    }

    @Test
    fun parsesMoreThanSixAgentsUpToTheBoundedControllerLimit() {
        val root = JSONObject(CONTROLLER_SNAPSHOT)
        val agents = JSONArray()
        repeat(30) { index ->
            agents.put(
                JSONObject()
                    .put("id", "agent-${index.toString(16).padStart(24, '0')}")
                    .put("label", "Task $index")
                    .put("state", if (index == 12) "waiting" else "idle")
                    .put("focused", index == 23)
                    .put("freshness", "fresh")
                    .put("actionable", true),
            )
        }
        root.getJSONObject("controller")
            .put("agents", agents)
            .put("focusedAgentId", "agent-${23.toString(16).padStart(24, '0')}")
            .put("focusedAgentIndex", 23)

        val snapshot = decode(root)

        assertEquals(MaxAgents, snapshot.desktop?.agents?.size)
        assertEquals("Task 0", snapshot.agentSlots().first().agent?.label)
        assertEquals(Activity.WAITING, snapshot.agentSlots()[12].agent?.activity)
        assertTrue(snapshot.agentSlots().any { it.focused })
    }

    @Test
    fun nativeAgentNavigationRemainsEnabledWhenCodexIsNotFrontmost() {
        val root = JSONObject(CONTROLLER_SNAPSHOT)
        root.getJSONObject("controller").put("foreground", false)

        val snapshot = decode(root)

        assertTrue(snapshot.agentFocusEnabled("agent-0123456789abcdef01234567"))
        assertTrue(snapshot.inputEnabled("touch"))
    }

    @Test
    fun selectedEmptyLayerDisablesMappedInputs() {
        val root = JSONObject(CONTROLLER_SNAPSHOT)
        root.getJSONObject("controller").put("activeLayerId", "layer-2")

        val snapshot = decode(root)

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

        val snapshot = decode(root)

        assertEquals("approve", snapshot.actionFor("key_accept", Gesture.Kind.TAP)?.type)
        assertNull(snapshot.actionFor("key_accept", Gesture.Kind.DOUBLE_TAP))
        assertTrue(snapshot.inputEnabled("key_accept", Gesture.Kind.TAP))
    }

    @Test
    fun oldSnapshotKeepsOnlyVerifiedLegacyControls() {
        val snapshot = decode(
            JSONObject(
                """
                {
                  "protocolVersion":$ProtocolVersion,
                  "revision":"r_old",
                  "observation":{"fresh":true,"observedAt":1700000000000},
                  "status":{"state":"ready","message":null},
                  "controls":{"voice":true,"stop":false,"new-task":true,"approve":false,"reject":true}
                }
                """.trimIndent(),
            ),
        )

        assertNull(snapshot.desktop)
        assertTrue(snapshot.inputEnabled("key_voice"))
        assertTrue(snapshot.inputEnabled("key_new_task"))
        assertTrue(snapshot.inputEnabled("key_reject"))
        assertTrue(snapshot.inputEnabled("key_attach"))
        assertFalse(snapshot.inputEnabled("key_mode"))
        assertFalse(snapshot.inputEnabled("joystick_up"))
        assertEquals(
            "voice",
            snapshot.commandFor("key_voice", Gesture.Kind.TAP).encode().getString("kind"),
        )
    }

    @Test
    fun malformedOptionalControllerDataFallsBackConservatively() {
        val snapshot = decode(
            JSONObject(
                """
                {
                  "protocolVersion":$ProtocolVersion,
                  "observation":{"fresh":true,"observedAt":1700000000000},
                  "status":{"state":"ready"},
                  "controller":{
                    "profile":{"version":-2,"inputs":[],"layers":[]},
                    "taskState":"teleporting",
                    "focusedAgentIndex":99,
                    "focusedAgentId":"agent-bbbbbbbbbbbbbbbbbbbbbbbb",
                    "agents":[
                      {"id":"agent-aaaaaaaaaaaaaaaaaaaaaaaa","label":"A","state":"unknown","focused":false,"freshness":"fresh","actionable":true},
                      {"id":"agent-not-hex","label":"Invalid ID","state":"complete"}
                    ],
                    "mode":{"available":"yes","label":null}
                  }
                }
                """.trimIndent(),
            ),
        )

        assertNull(snapshot.desktop?.profile)
        assertEquals(Activity.IDLE, snapshot.desktop?.activity)
        assertEquals(-1, snapshot.desktop?.focusedAgentIndex)
        assertNull(snapshot.desktop?.focusedAgentId)
        assertEquals(
            listOf(Agent("agent-aaaaaaaaaaaaaaaaaaaaaaaa", "A", Activity.IDLE, false)),
            snapshot.desktop?.agents,
        )
        assertFalse(snapshot.desktop?.mode?.available ?: true)
        assertFalse(snapshot.inputEnabled("key_mode"))
    }

    @Test
    fun serializesOnlyStructuredControllerCommands() {
        val binding = Command.Binding(
            "key_voice",
            Gesture.Kind.DOUBLE_TAP,
            "layer-2",
            Action("workflow", workflowId = "debug"),
        ).encode()
        val layer = Command.SelectLayer("layer-3").encode()
        val focus = Command.SelectAgent("agent-444444444444444444444444").encode()
        val update = Command.UpdateBinding(
            "layer-2",
            "key_voice",
            Gesture.Kind.HOLD,
            Action("workflow", workflowId = "debug"),
        ).encode()
        val clear = Command.ClearBinding(
            "layer-2",
            "key_voice",
            Gesture.Kind.DOUBLE_TAP,
        ).encode()
        val rename = Command.RenameLayer("layer-2", "Research").encode()
        val color = Command.UpdateLayerColor("layer-2", "#55D6A4").encode()
        val workflow = Command.UpdateWorkflowPrompt("debug", "Investigate from evidence.").encode()
        val reset = Command.ResetProfile.encode()
        val model = Command.SelectModel(Target, "gpt-test").encode()
        val mode = Command.SelectMode(Target, "plan").encode()
        val reasoning = Command.SelectReasoning(Target, Reasoning.Level.HIGH).encode()

        assertEquals("binding", binding.getString("kind"))
        assertEquals("key_voice", binding.getString("inputId"))
        assertEquals("double_tap", binding.getString("gesture"))
        assertEquals("layer-2", binding.getString("layerId"))
        assertEquals("debug", binding.getJSONObject("action").getString("workflowId"))
        assertEquals("select_layer", layer.getString("kind"))
        assertEquals("layer-3", layer.getString("layerId"))
        assertEquals("select_agent", focus.getString("kind"))
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
        assertEquals("voice_start", Command.VoiceStart.encode().getString("kind"))
        assertEquals("voice_stop", Command.VoiceStop.encode().getString("kind"))
        assertEquals("model_picker", Command.ModelPicker.encode().getString("kind"))
        assertEquals("select_model", model.getString("kind"))
        assertEquals("gpt-test", model.getString("modelId"))
        assertEquals(Target.threadId, model.getJSONObject("target").getString("threadId"))
        assertEquals("select_mode", mode.getString("kind"))
        assertEquals("plan", mode.getString("modeId"))
        assertEquals("select_reasoning", reasoning.getString("kind"))
        assertEquals("high", reasoning.getString("level"))
        assertEquals(Command.SelectModel(Target, "gpt-test"), decodeCommand(model))
        assertThrows(IllegalArgumentException::class.java) {
            decodeCommand(JSONObject().put("kind", "select_model").put("modelId", "gpt-test"))
        }
    }

    private companion object {
        val CONTROLLER_SNAPSHOT = """
            {
              "protocolVersion":$ProtocolVersion,
              "revision":"r_42",
              "observation":{"fresh":true,"observedAt":1700000000000},
              "sources":{
                "appServer":{"fresh":true,"observedAt":1700000000000},
                "desktopUI":{"fresh":false,"observedAt":1700000000000}
              },
              "status":{"state":"ready","message":"Current desktop task"},
              "controls":{
                "voice":true,"stop":false,"new-task":true,"approve":true,"reject":true,
                "clear-input":true,"focus-agent":true,"mode-cycle":true,"model-picker":true,"model":true,"navigate":true,
                "access-cycle":true,"reasoning":true,"workflow":true
              },
              "controller":{
                "activeLayerId":"layer-1",
                "foreground":true,
                "taskState":"waiting",
                "focusedAgentIndex":0,
                "focusedAgentId":"agent-0123456789abcdef01234567",
                "binding":{
                  "target":{"state":"bound","threadId":"thread-1","agentId":"agent-0123456789abcdef01234567","bindingEpoch":4,"bridgeInstanceId":"bridge-1","appServerGeneration":7,"canonicalWorkspaceId":"workspace-1"},
                  "visible":{"state":"confirmed","contextId":"agent-0123456789abcdef01234567"}
                },
                "tasks":{"availability":"fresh","message":null},
                "voice":{"available":true,"active":false},
                "agents":[
                  {"id":"agent-0123456789abcdef01234567","label":"Turing","state":"thinking","focused":false,"freshness":"fresh","actionable":true},
                  {"id":"agent-89abcdef0123456789abcdef","label":"Dalton","state":"unread","focused":false,"freshness":"fresh","actionable":true}
                ],
                "mode":{"available":true,"label":"Codex"},
                "access":{"available":true,"label":"Workspace"},
                "model":{
                  "available":true,"id":"gpt-test","label":"GPT Test",
                  "options":[
                    {"id":"gpt-test","label":"GPT Test","selected":true},
                    {"id":"gpt-next","label":"GPT Next","selected":false}
                  ]
                },
                "reasoning":{
                  "available":true,"label":"High","level":"high",
                  "canIncrease":true,"canDecrease":true,
                  "increaseTo":"xhigh","decreaseTo":"medium"
                },
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

        val Target = TargetRef(
            threadId = "thread-1",
            agentId = "agent-0123456789abcdef01234567",
            bindingEpoch = 4,
            bridgeInstanceId = "bridge-1",
            appServerGeneration = 7,
            canonicalWorkspaceId = "workspace-1",
        )
    }
}
