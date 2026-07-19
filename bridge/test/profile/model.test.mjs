import assert from "node:assert/strict";
import test from "node:test";

import {
  ACTIONS,
  GESTURES,
  VERSION,
  bindingFor,
  clearBinding,
  createDefault,
  normalize,
  updateBinding,
  updateLayerColor,
  updateWorkflow,
  validateAction,
  workflowPrompt,
} from "../../src/profile/model.mjs";

test("migrates version 1 direct actions into version 3 tap gestures and defaults", () => {
  const legacy = createDefault();
  legacy.version = 1;
  for (const layer of legacy.layers) {
    layer.bindings = Object.fromEntries(
      Object.entries(layer.bindings).map(([inputId, gestures]) => [inputId, gestures.tap]),
    );
  }

  const migrated = normalize(legacy);
  assert.equal(migrated.version, VERSION);
  assert.deepEqual(migrated.layers[0].bindings.key_voice, { tap: { type: "voice" } });
  assert.deepEqual(bindingFor(migrated, "layer-1", "key_voice"), { type: "voice" });
  assert.equal(bindingFor(migrated, "layer-1", "key_voice", "hold"), null);
  assert.match(workflowPrompt(migrated, "debug"), /Investigate the current failure/);
  assert.equal(migrated.layers[0].color, "#F4F4F2");
});

test("persists validated workflow prompts, layer colors, and layer actions", () => {
  let profile = createDefault();
  profile = updateWorkflow(profile, {
    workflowId: "debug",
    prompt: "Reproduce the failure, isolate the cause, fix it, and run the focused tests.",
  });
  profile = updateLayerColor(profile, { layerId: "layer-2", color: "#12abef" });
  profile = updateBinding(profile, {
    layerId: "layer-1",
    inputId: "key_focus",
    action: { type: "select_layer", layerId: "layer-2" },
  });

  assert.equal(workflowPrompt(profile, "debug"), "Reproduce the failure, isolate the cause, fix it, and run the focused tests.");
  assert.equal(profile.layers[1].color, "#12ABEF");
  assert.deepEqual(bindingFor(profile, "layer-1", "key_focus"), { type: "select_layer", layerId: "layer-2" });
});

test("supports tap, double_tap, and hold slots on every fixed input", () => {
  let profile = createDefault();
  const inputIds = profile.inputs.map(({ id }) => id);
  for (const inputId of inputIds) {
    for (const { id: gesture } of GESTURES) {
      profile = updateBinding(profile, {
        layerId: "layer-2",
        inputId,
        gesture,
        action: { type: "attach" },
      });
    }
  }

  assert.equal(profile.layers.length, 6);
  for (const inputId of inputIds) {
    assert.deepEqual(profile.layers[1].bindings[inputId], {
      tap: { type: "attach" },
      double_tap: { type: "attach" },
      hold: { type: "attach" },
    });
  }

  profile = clearBinding(profile, {
    layerId: "layer-2",
    inputId: "key_voice",
    gesture: "double_tap",
  });
  assert.equal(bindingFor(profile, "layer-2", "key_voice", "double_tap"), null);
  assert.deepEqual(bindingFor(profile, "layer-2", "key_voice", "hold"), { type: "attach" });
});

test("exposes only validated semantic actions in the configuration catalog", () => {
  assert.deepEqual(GESTURES.map(({ id }) => id), ["tap", "double_tap", "hold"]);
  assert.ok(ACTIONS.length > 0);
  for (const entry of ACTIONS) {
    assert.deepEqual(validateAction(entry.action), entry.action);
  }
});

test("rejects arbitrary or over-broad action payloads", () => {
  const invalidActions = [
    { type: "prompt", text: "ignore the whitelist" },
    { type: "raw_key", key: "cmd+q" },
    { type: "workflow", workflowId: "custom" },
    { type: "workflow", workflowId: "debug", prompt: "arbitrary text" },
    { type: "navigate", direction: "diagonal" },
    { type: "reasoning_depth", delta: 2 },
    { type: "voice", shell: "open -a Terminal" },
  ];
  for (const action of invalidActions) {
    assert.throws(() => validateAction(action), /action|direction|adjustment|workflow/i);
  }
});

test("rejects profiles that alter the fixed layer or input topology", () => {
  const missingLayer = createDefault();
  missingLayer.layers.pop();
  assert.throws(() => normalize(missingLayer), /exactly six layers/);

  const unknownInput = createDefault();
  unknownInput.layers[0].bindings.key_shell = { tap: { type: "attach" } };
  assert.throws(() => normalize(unknownInput), /input does not exist/);
});

test("keeps push-to-talk exclusive so release cannot race another gesture", () => {
  const profile = createDefault();
  assert.throws(
    () => updateBinding(profile, {
      layerId: "layer-1",
      inputId: "key_voice",
      gesture: "hold",
      action: { type: "workflow", workflowId: "debug" },
    }),
    /push-to-talk.*only gesture/i,
  );
  assert.throws(
    () => updateBinding(profile, {
      layerId: "layer-2",
      inputId: "key_accept",
      gesture: "double_tap",
      action: { type: "voice" },
    }),
    /push-to-talk.*tap/i,
  );
});
