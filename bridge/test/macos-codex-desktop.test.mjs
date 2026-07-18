import assert from "node:assert/strict";
import test from "node:test";

import { MacCodexDesktopController } from "../src/macos-codex-desktop.mjs";

function controllerFixture() {
  const calls = [];
  const run = async (socketPath, action, args, input = "") => {
    calls.push([socketPath, action, args, input]);
    return {
      ok: true,
      message: action,
      controls: {},
    };
  };
  const controller = new MacCodexDesktopController({
    socketPath: "/tmp/vibe-pocket-test.sock",
    run,
    openThread: async (threadId) => { calls.push(["openThread", threadId]); },
    wait: async (milliseconds) => { calls.push(["wait", milliseconds]); },
  });
  return { calls, controller };
}

test("maps semantic Codex controls to the prebuilt Swift helper", async () => {
  const { calls, controller } = controllerFixture();

  await controller.status();
  await controller.activate();
  await controller.press("new-task");
  await controller.cycleMode();
  await controller.cycleAccess();
  await controller.adjustReasoning(-1);
  await controller.clearInput();
  await controller.workflow("Review the visible task.");

  assert.deepEqual(calls, [
    ["/tmp/vibe-pocket-test.sock", "status", [], ""],
    ["/tmp/vibe-pocket-test.sock", "attach", [], ""],
    ["/tmp/vibe-pocket-test.sock", "control", ["new-task"], ""],
    ["/tmp/vibe-pocket-test.sock", "plan-mode", [], ""],
    ["/tmp/vibe-pocket-test.sock", "access-cycle", [], ""],
    ["/tmp/vibe-pocket-test.sock", "reasoning", ["-1"], ""],
    ["/tmp/vibe-pocket-test.sock", "clear-input", [], ""],
    ["/tmp/vibe-pocket-test.sock", "workflow", [], "Review the visible task."],
  ]);
});

test("keeps phone voice local and sends only the recognized draft to Swift", async () => {
  const { calls, controller } = controllerFixture();

  assert.equal(controller.voiceActive, false);
  await controller.setVoice(true);
  assert.equal(controller.voiceActive, true);
  assert.deepEqual(calls, []);

  await controller.setDictationDraft("Phone transcript");
  assert.equal(controller.voiceActive, false);
  assert.deepEqual(calls, [
    ["/tmp/vibe-pocket-test.sock", "dictation-draft", [], "Phone transcript"],
  ]);
});

test("opens an exact desktop task before attaching without starting Codex app-server", async () => {
  const { calls, controller } = controllerFixture();

  await controller.bindThread("019f2ce2-e042-7ab0-a73d-9fa41d58e210");
  assert.deepEqual(calls, [
    ["openThread", "019f2ce2-e042-7ab0-a73d-9fa41d58e210"],
    ["wait", 700],
    ["/tmp/vibe-pocket-test.sock", "attach", [], ""],
  ]);
  const lifecycle = controller.applyLifecycleHook("Stop", {});
  assert.equal(lifecycle.accepted, false);
  assert.deepEqual(await lifecycle.response, {});
});
