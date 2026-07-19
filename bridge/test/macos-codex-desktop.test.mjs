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

test("routes push-to-talk to the verified desktop dictation state", async () => {
  const { calls, controller } = controllerFixture();

  assert.equal(controller.voiceActive, false);
  await controller.setVoice(true);
  assert.equal(controller.voiceActive, true);
  await controller.setVoice(false);
  assert.equal(controller.voiceActive, false);
  assert.deepEqual(calls, [
    ["/tmp/vibe-pocket-test.sock", "voice-start", [], ""],
    ["/tmp/vibe-pocket-test.sock", "voice-stop", [], ""],
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

test("resolves and focuses Agent keys through native task links instead of Accessibility", async () => {
  const calls = [];
  const threadCatalog = {
    async resolveVisibleAgents(agents) {
      calls.push(["resolveVisibleAgents", agents]);
      return [
        { id: "agent-111111111111111111111111", label: "Current", state: "idle", focused: true },
        { id: "agent-222222222222222222222222", label: "Target", state: "idle", focused: false },
      ];
    },
    async focusAgent(agentId) {
      calls.push(["nativeFocus", agentId]);
      return { ok: true, message: "opened" };
    },
    async dispose() {
      calls.push(["dispose"]);
    },
  };
  const controller = new MacCodexDesktopController({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog,
    run: async (socketPath, action, args, input) => {
      calls.push([socketPath, action, args, input]);
      return {
        ok: true,
        foreground: true,
        controls: { "focus-agent": true },
        agents: [{ id: "agent-aaaaaaaaaaaaaaaaaaaaaaaa", label: "AX row", state: "idle", focused: true }],
      };
    },
  });

  const status = await controller.status();
  assert.equal(status.controls["focus-agent"], true);
  assert.deepEqual(status.agents.map(({ label }) => label), ["Current", "Target"]);
  await controller.focusAgent(status.agents[1].id);
  await controller.dispose();

  assert.deepEqual(calls.map((call) => call[0]), [
    "/tmp/vibe-pocket-test.sock",
    "resolveVisibleAgents",
    "nativeFocus",
    "dispose",
  ]);
  assert.ok(!calls.some((call) => call[1] === "focus-agent"));
});

test("disables native Agent navigation while Codex is not frontmost", async () => {
  let focused = false;
  const controller = new MacCodexDesktopController({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      async resolveVisibleAgents() {
        return [{ id: "agent-111111111111111111111111", label: "Target", state: "idle", focused: false }];
      },
      async focusAgent() { focused = true; },
    },
    run: async () => ({
      ok: true,
      foreground: false,
      controls: { "focus-agent": true },
      agents: [],
    }),
  });

  const status = await controller.status();
  assert.equal(status.controls["focus-agent"], false);
  await assert.rejects(() => controller.focusAgent(status.agents[0].id), /Open Codex on the Mac/i);
  assert.equal(focused, false);
});

test("retains the last task catalog across a transient catalog refresh failure", async () => {
  let failCatalog = false;
  const agents = [
    { id: "agent-111111111111111111111111", label: "Current", state: "idle", focused: true },
    { id: "agent-222222222222222222222222", label: "Recent", state: "idle", focused: false },
  ];
  const controller = new MacCodexDesktopController({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      async resolveVisibleAgents() {
        if (failCatalog) throw new Error("temporary app-server failure");
        return agents;
      },
    },
    run: async () => ({
      ok: true,
      foreground: true,
      controls: { "focus-agent": true },
      agents: [],
    }),
  });

  assert.deepEqual((await controller.status()).agents, agents);
  failCatalog = true;
  const recovered = await controller.status();

  assert.deepEqual(recovered.agents, agents);
  assert.equal(recovered.controls["focus-agent"], true);
});

test("does not queue native Agent navigation behind a slow Accessibility scan", async () => {
  let statusCalls = 0;
  let releaseScan;
  const slowScan = new Promise((resolve) => { releaseScan = resolve; });
  const nativeFocuses = [];
  const threadCatalog = {
    async resolveVisibleAgents() {
      return [
        { id: "agent-111111111111111111111111", label: "Current", state: "idle", focused: true },
        { id: "agent-222222222222222222222222", label: "Target", state: "idle", focused: false },
      ];
    },
    async focusAgent(agentId) {
      nativeFocuses.push(agentId);
      return { ok: true, message: "opened" };
    },
  };
  const controller = new MacCodexDesktopController({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog,
    run: async () => {
      statusCalls += 1;
      if (statusCalls === 2) await slowScan;
      return { ok: true, foreground: true, controls: {}, agents: [] };
    },
  });

  const initial = await controller.status();
  const pendingScan = controller.status();
  await controller.focusAgent(initial.agents[1].id);

  assert.deepEqual(nativeFocuses, ["agent-222222222222222222222222"]);
  releaseScan();
  await pendingScan;
});
