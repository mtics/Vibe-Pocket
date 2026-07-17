import assert from "node:assert/strict";
import test from "node:test";

import {
  CONTROLLER_ACTION_CATALOG,
  CONTROLLER_GESTURES,
  CONTROLLER_PROFILE_VERSION,
  bindingFor,
  clearControllerBinding,
  createDefaultControllerProfile,
  normalizeControllerProfile,
  updateControllerBinding,
  validateControllerAction,
} from "../src/controller-profile.mjs";

test("migrates version 1 direct actions into version 2 tap gestures", () => {
  const legacy = createDefaultControllerProfile();
  legacy.version = 1;
  for (const layer of legacy.layers) {
    layer.bindings = Object.fromEntries(
      Object.entries(layer.bindings).map(([inputId, gestures]) => [inputId, gestures.tap]),
    );
  }

  const migrated = normalizeControllerProfile(legacy);
  assert.equal(migrated.version, CONTROLLER_PROFILE_VERSION);
  assert.deepEqual(migrated.layers[0].bindings.key_voice, { tap: { type: "voice" } });
  assert.deepEqual(bindingFor(migrated, "layer-1", "key_voice"), { type: "voice" });
  assert.equal(bindingFor(migrated, "layer-1", "key_voice", "hold"), null);
});

test("supports tap, double_tap, and hold slots on every fixed input", () => {
  let profile = createDefaultControllerProfile();
  const inputIds = profile.inputs.map(({ id }) => id);
  for (const inputId of inputIds) {
    for (const { id: gesture } of CONTROLLER_GESTURES) {
      profile = updateControllerBinding(profile, {
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

  profile = clearControllerBinding(profile, {
    layerId: "layer-2",
    inputId: "key_voice",
    gesture: "double_tap",
  });
  assert.equal(bindingFor(profile, "layer-2", "key_voice", "double_tap"), null);
  assert.deepEqual(bindingFor(profile, "layer-2", "key_voice", "hold"), { type: "attach" });
});

test("exposes only validated semantic actions in the configuration catalog", () => {
  assert.deepEqual(CONTROLLER_GESTURES.map(({ id }) => id), ["tap", "double_tap", "hold"]);
  assert.ok(CONTROLLER_ACTION_CATALOG.length > 0);
  for (const entry of CONTROLLER_ACTION_CATALOG) {
    assert.deepEqual(validateControllerAction(entry.action), entry.action);
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
    assert.throws(() => validateControllerAction(action), /action|direction|adjustment|workflow/i);
  }
});

test("rejects profiles that alter the fixed layer or input topology", () => {
  const missingLayer = createDefaultControllerProfile();
  missingLayer.layers.pop();
  assert.throws(() => normalizeControllerProfile(missingLayer), /exactly six layers/);

  const unknownInput = createDefaultControllerProfile();
  unknownInput.layers[0].bindings.key_shell = { tap: { type: "attach" } };
  assert.throws(() => normalizeControllerProfile(unknownInput), /input does not exist/);
});

test("keeps push-to-talk exclusive so release cannot race another gesture", () => {
  const profile = createDefaultControllerProfile();
  assert.throws(
    () => updateControllerBinding(profile, {
      layerId: "layer-1",
      inputId: "key_voice",
      gesture: "hold",
      action: { type: "workflow", workflowId: "debug" },
    }),
    /push-to-talk.*only gesture/i,
  );
  assert.throws(
    () => updateControllerBinding(profile, {
      layerId: "layer-2",
      inputId: "key_accept",
      gesture: "double_tap",
      action: { type: "voice" },
    }),
    /push-to-talk.*tap/i,
  );
});
