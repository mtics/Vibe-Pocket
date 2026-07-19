import assert from "node:assert/strict";
import test from "node:test";

import { Desktop } from "../../src/macos/desktop.mjs";

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
  const controller = new Desktop({
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
  await controller.openModel();
  await controller.deleteBackward();
  await controller.clearInput();
  await controller.workflow("Review the visible task.");

  assert.deepEqual(calls, [
    ["/tmp/vibe-pocket-test.sock", "status", [], ""],
    ["/tmp/vibe-pocket-test.sock", "attach", [], ""],
    ["/tmp/vibe-pocket-test.sock", "control", ["new-task"], ""],
    ["/tmp/vibe-pocket-test.sock", "plan-mode", [], ""],
    ["/tmp/vibe-pocket-test.sock", "access-cycle", [], ""],
    ["/tmp/vibe-pocket-test.sock", "model-picker", [], ""],
    ["/tmp/vibe-pocket-test.sock", "delete-backward", [], ""],
    ["/tmp/vibe-pocket-test.sock", "clear-input", [], ""],
    ["/tmp/vibe-pocket-test.sock", "workflow", [], "Review the visible task."],
  ]);
});

test("changes model and reasoning in the visible desktop before confirming state", async () => {
  const calls = [];
  const settings = {
    model: {
      available: true,
      id: "gpt-sol",
      label: "Sol",
      options: [{ id: "gpt-sol", label: "Sol", selected: true }],
    },
    reasoning: {
      available: true,
      label: "Minimal",
      level: "minimal",
      canIncrease: true,
      canDecrease: false,
    },
  };
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      async resolveVisibleAgents() { return []; },
      async settings() { return settings; },
      async validateModel(modelId) { return settings.model.options.find(({ id }) => id === modelId); },
      async selectModel(modelId) {
        settings.model = {
          ...settings.model,
          id: modelId,
          options: settings.model.options.map((option) => ({ ...option, selected: option.id === modelId })),
        };
        return settings;
      },
      reasoningTarget() { return "low"; },
      async selectReasoning(level) {
        settings.reasoning = {
          available: true,
          label: "Low",
          level,
          canIncrease: true,
          canDecrease: true,
        };
        return settings;
      },
    },
    run: async (_socketPath, action, args) => {
      calls.push(["helper", action, args]);
      return {
        ok: true,
        foreground: true,
        controls: { "model-picker": true, reasoning: true },
        agents: [],
        reasoning: { modelLabel: "Sol", level: "minimal" },
      };
    },
  });

  const status = await controller.status();
  assert.equal(status.controls.model, true);
  assert.equal(status.controls.reasoning, true);
  await controller.selectModel("gpt-sol");
  await controller.adjustReasoning(1);

  assert.deepEqual(calls, [
    ["helper", "status", []],
    ["helper", "status", []],
    ["helper", "select-model", ["gpt-sol"]],
    ["helper", "status", []],
    ["helper", "select-reasoning", ["low"]],
  ]);
});

test("rejects stale model IDs before any prefix-colliding desktop option can be pressed", async () => {
  const calls = [];
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      async resolveVisibleAgents() { return []; },
      async settings() {
        return {
          model: {
            available: true,
            id: "gpt-solar",
            label: "Solar",
            options: [{ id: "gpt-solar", label: "Solar", selected: true }],
          },
          reasoning: { available: true, label: "Low", level: "low" },
        };
      },
      async validateModel() { throw new Error("The requested Codex model is unavailable or stale."); },
    },
    run: async (_socketPath, action, args) => {
      calls.push([action, args]);
      return {
        ok: true,
        taskState: "idle",
        controls: { "model-picker": true, reasoning: true },
        agents: [],
        reasoning: { modelLabel: "Solar", level: "low" },
      };
    },
  });

  await assert.rejects(() => controller.selectModel("gpt-sol"), /unavailable or stale/i);
  assert.deepEqual(calls, [["status", []]]);
});

test("rejects both delta and exact reasoning changes while Stop is visible", async () => {
  const calls = [];
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      async resolveVisibleAgents() { return []; },
      async settings() {
        return {
          model: { available: true, id: "gpt-sol", label: "Sol", options: [] },
          reasoning: {
            available: true,
            label: "High",
            level: "high",
            canIncrease: true,
            canDecrease: true,
          },
        };
      },
      reasoningTarget() { return "xhigh"; },
      hasReasoningLevel() { return true; },
    },
    run: async (_socketPath, action, args) => {
      calls.push([action, args]);
      return {
        ok: true,
        taskState: "executing",
        controls: { stop: true, "model-picker": false, reasoning: false },
        agents: [],
        reasoning: { modelLabel: "Sol", level: "high" },
      };
    },
  });

  await assert.rejects(() => controller.adjustReasoning(1), /while .* running/i);
  await assert.rejects(() => controller.selectReasoning("xhigh"), /while .* running/i);
  assert.deepEqual(calls, [["status", []], ["status", []]]);
});

test("does not advertise companion settings while visible desktop controls are unavailable", async () => {
  const settings = {
    model: { available: true, id: "gpt-sol", label: "Sol", options: [] },
    reasoning: { available: true, label: "Medium", level: "medium", canIncrease: true, canDecrease: true },
  };
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      async resolveVisibleAgents() { return []; },
      async settings() { return settings; },
    },
    run: async () => ({
      ok: true,
      foreground: false,
      controls: { "model-picker": false, reasoning: false },
      agents: [],
      reasoning: { modelLabel: "Sol", level: "medium" },
    }),
  });

  const status = await controller.status();

  assert.equal(status.controls.model, false);
  assert.equal(status.controls.reasoning, false);
  assert.equal(status.model.available, false);
  assert.equal(status.reasoning.available, false);
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

test("opens an exact desktop task and attaches only after catalog focus confirmation", async () => {
  const calls = [];
  let opened = false;
  const targetId = "agent-111111111111111111111111";
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    focusPollAttempts: 2,
    wait: async (milliseconds) => { calls.push(["wait", milliseconds]); },
    threadCatalog: {
      async focusThread(threadId) {
        calls.push(["focusThread", threadId]);
        opened = true;
        return { agentId: targetId };
      },
      async resolveVisibleAgents() {
        calls.push(["resolveVisibleAgents"]);
        return [{ id: targetId, label: "Bound", state: "idle", focused: opened }];
      },
    },
    run: async (socketPath, action, args, input = "") => {
      calls.push([socketPath, action, args, input]);
      return { ok: true, controls: {}, agents: [] };
    },
  });

  await controller.bindThread("019f2ce2-e042-7ab0-a73d-9fa41d58e210");
  assert.deepEqual(calls, [
    ["focusThread", "019f2ce2-e042-7ab0-a73d-9fa41d58e210"],
    ["/tmp/vibe-pocket-test.sock", "status", [], ""],
    ["resolveVisibleAgents"],
    ["/tmp/vibe-pocket-test.sock", "attach", [], ""],
  ]);
  const lifecycle = controller.applyLifecycleHook("Stop", {});
  assert.equal(lifecycle.accepted, false);
  assert.deepEqual(await lifecycle.response, {});
});

test("resolves and focuses Agent keys through native task links instead of Accessibility", async () => {
  const calls = [];
  let requestedFocus = null;
  const threadCatalog = {
    async resolveVisibleAgents(agents) {
      calls.push(["resolveVisibleAgents", agents]);
      return [
        { id: "agent-111111111111111111111111", label: "Current", state: "idle", focused: requestedFocus === null },
        { id: "agent-222222222222222222222222", label: "Target", state: "idle", focused: requestedFocus === "agent-222222222222222222222222" },
      ];
    },
    async focusAgent(agentId) {
      calls.push(["nativeFocus", agentId]);
      requestedFocus = agentId;
      return { ok: true, message: "opened", agentId };
    },
    async dispose() {
      calls.push(["dispose"]);
    },
  };
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog,
    focusPollAttempts: 2,
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
    "/tmp/vibe-pocket-test.sock",
    "resolveVisibleAgents",
    "dispose",
  ]);
  assert.ok(!calls.some((call) => call[1] === "focus-agent"));
});

test("allows native Agent navigation while Codex is not frontmost", async () => {
  let focused = false;
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      async resolveVisibleAgents() {
        return [{ id: "agent-111111111111111111111111", label: "Target", state: "idle", focused }];
      },
      async focusAgent(agentId) { focused = true; return { agentId }; },
    },
    run: async () => ({
      ok: true,
      foreground: false,
      controls: { "focus-agent": true },
      agents: [],
    }),
    focusPollAttempts: 2,
  });

  const status = await controller.status();
  assert.equal(status.controls["focus-agent"], true);
  await controller.focusAgent(status.agents[0].id);
  assert.equal(focused, true);
});

test("holds a following desktop action until delayed deep-link focus is observed", async () => {
  const calls = [];
  let observations = 0;
  const currentId = "agent-111111111111111111111111";
  const targetId = "agent-222222222222222222222222";
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    focusPollAttempts: 4,
    focusPollIntervalMs: 25,
    wait: async (milliseconds) => { calls.push(["wait", milliseconds]); },
    threadCatalog: {
      async focusAgent(agentId) {
        calls.push(["open", agentId]);
        return { agentId };
      },
      async resolveVisibleAgents() {
        observations += 1;
        const targetFocused = observations >= 3;
        calls.push(["observe", targetFocused ? "target" : "current"]);
        return [
          { id: currentId, label: "Current", state: "idle", focused: !targetFocused },
          { id: targetId, label: "Target", state: "idle", focused: targetFocused },
        ];
      },
    },
    run: async (_socketPath, action) => {
      calls.push(["helper", action]);
      return { ok: true, controls: {}, agents: [] };
    },
  });

  const focus = controller.focusAgent(targetId);
  const action = controller.press("new-task");
  await Promise.all([focus, action]);

  assert.ok(calls.findIndex((call) => call[0] === "helper" && call[1] === "control")
    > calls.findIndex((call) => call[0] === "observe" && call[1] === "target"));
});

test("times out a no-op deep link without executing its dependent action", async () => {
  const calls = [];
  const currentId = "agent-111111111111111111111111";
  const targetId = "agent-222222222222222222222222";
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    focusPollAttempts: 3,
    wait: async () => {},
    threadCatalog: {
      async focusAgent(agentId) { calls.push(["open", agentId]); return { agentId }; },
      async resolveVisibleAgents() {
        calls.push(["observe", "current"]);
        return [
          { id: currentId, label: "Current", state: "idle", focused: true },
          { id: targetId, label: "Target", state: "idle", focused: false },
        ];
      },
    },
    run: async (_socketPath, action) => {
      calls.push(["helper", action]);
      return { ok: true, controls: {}, agents: [] };
    },
  });

  await assert.rejects(async () => {
    await controller.focusAgent(targetId);
    await controller.press("new-task");
  }, /focus timeout/i);

  assert.equal(calls.filter((call) => call[0] === "observe").length, 3);
  assert.equal(calls.some((call) => call[0] === "helper" && call[1] === "control"), false);
});

test("never confirms focus from the cached catalog when fresh observations fail", async () => {
  const targetId = "agent-222222222222222222222222";
  let failCatalog = false;
  let catalogCalls = 0;
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    focusPollAttempts: 3,
    wait: async () => {},
    threadCatalog: {
      async focusAgent(agentId) { return { agentId }; },
      async resolveVisibleAgents() {
        catalogCalls += 1;
        if (failCatalog) throw new Error("temporary app-server failure");
        return [{ id: targetId, label: "Target", state: "idle", focused: true }];
      },
    },
    run: async () => ({ ok: true, controls: {}, agents: [] }),
  });

  await controller.status();
  failCatalog = true;

  await assert.rejects(() => controller.focusAgent(targetId), /focus timeout/i);
  assert.equal(catalogCalls, 4);
});

test("retains the last task catalog across a transient catalog refresh failure", async () => {
  let failCatalog = false;
  const agents = [
    { id: "agent-111111111111111111111111", label: "Current", state: "idle", focused: true },
    { id: "agent-222222222222222222222222", label: "Recent", state: "idle", focused: false },
  ];
  const controller = new Desktop({
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

test("serializes native Agent navigation behind an in-flight Accessibility scan", async () => {
  let statusCalls = 0;
  let opened = false;
  let releaseScan;
  const slowScan = new Promise((resolve) => { releaseScan = resolve; });
  const nativeFocuses = [];
  const threadCatalog = {
    async resolveVisibleAgents() {
      return [
        { id: "agent-111111111111111111111111", label: "Current", state: "idle", focused: !opened },
        { id: "agent-222222222222222222222222", label: "Target", state: "idle", focused: opened },
      ];
    },
    async focusAgent(agentId) {
      nativeFocuses.push(agentId);
      opened = true;
      return { ok: true, message: "opened", agentId };
    },
  };
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog,
    focusPollAttempts: 2,
    run: async () => {
      statusCalls += 1;
      if (statusCalls === 2) await slowScan;
      return { ok: true, foreground: true, controls: {}, agents: [] };
    },
  });

  const initial = await controller.status();
  const pendingScan = controller.status();
  const pendingFocus = controller.focusAgent(initial.agents[1].id);

  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(nativeFocuses, []);
  releaseScan();
  await pendingScan;
  await pendingFocus;
  assert.deepEqual(nativeFocuses, ["agent-222222222222222222222222"]);
});
