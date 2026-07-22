import assert from "node:assert/strict";
import test from "node:test";

import { EffectBoundary } from "../../src/control/effects.mjs";
import { Desktop, prepareControlRequest } from "../../src/macos/desktop.mjs";
import { agentIdForThread } from "../../src/task/catalog.mjs";

const FOCUSED_AGENT_ID = agentIdForThread("thread-1");
const TARGET = Object.freeze({
  threadId: "thread-1",
  agentId: FOCUSED_AGENT_ID,
  bindingEpoch: 1,
  bridgeInstanceId: "bridge-aaaaaaaaaaaaaaaa",
  appServerGeneration: 0,
  canonicalWorkspaceId: "workspace-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
});

function effectBoundary(calls = []) {
  let crossed = false;
  return {
    get crossed() { return crossed; },
    async commit(operation) {
      assert.equal(crossed, false);
      crossed = true;
      calls.push(["effect", "commit"]);
      return operation();
    },
  };
}

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

test("sends a bounded absolute deadline in the Host IPC envelope", () => {
  const nowMilliseconds = 1_700_000_000_000;
  const request = prepareControlRequest(
    "control",
    ["new-task"],
    "",
    60_000,
    () => nowMilliseconds,
  );

  assert.deepEqual(request, {
    envelope: {
      action: "control",
      arguments: ["new-task"],
      input: "",
      deadlineMs: nowMilliseconds + 10_000,
    },
    timeoutMs: 10_000,
  });
});

test("maps semantic Codex controls to the prebuilt Swift helper", async () => {
  const { calls, controller } = controllerFixture();

  await controller.status();
  await controller.activate();
  await controller.press("new-task");
  await controller.cycleMode();
  await controller.cycleAccess();
  await controller.openModel();
  await controller.configureMicro();
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
    ["/tmp/vibe-pocket-test.sock", "configure-micro-reasoning", [], ""],
    ["/tmp/vibe-pocket-test.sock", "delete-backward", [], ""],
    ["/tmp/vibe-pocket-test.sock", "clear-input", [], ""],
    ["/tmp/vibe-pocket-test.sock", "workflow", [], "Review the visible task."],
  ]);
});

test("writes exact model and reasoning selections through app-server without desktop automation", async () => {
  const calls = [];
  const settings = {
    target: TARGET,
    model: {
      available: true,
      id: "gpt-sol",
      label: "Sol",
      options: [
        { id: "gpt-sol", label: "Sol", selected: true },
        { id: "gpt-luna", label: "Luna", selected: false },
      ],
    },
    reasoning: {
      available: true,
      label: "Minimal",
      level: "minimal",
      options: ["minimal", "low"],
      canIncrease: true,
      canDecrease: false,
    },
  };
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      target: TARGET,
      async resolveVisibleAgents() {
        return [{ id: FOCUSED_AGENT_ID, label: "Focused task", focused: true }];
      },
      async settings() { return settings; },
      async validateModel({ id, target }) {
        assert.deepEqual(target, TARGET);
        return settings.model.options.find((option) => option.id === id);
      },
      async selectModel({ id, target, effects }) {
        assert.deepEqual(target, TARGET);
        await effects.commit(async () => { calls.push(["app-server", "model", id]); });
        settings.model = {
          ...settings.model,
          id,
          label: "Luna",
          options: settings.model.options.map((option) => ({ ...option, selected: option.id === id })),
        };
        return settings;
      },
      reasoningTarget() { return "low"; },
      requireTarget(target) { assert.deepEqual(target, TARGET); },
      hasReasoningLevel(level) { return ["minimal", "low"].includes(level); },
      async selectReasoning({ level, target, effects }) {
        assert.deepEqual(target, TARGET);
        await effects.commit(async () => { calls.push(["app-server", "reasoning", level]); });
        settings.reasoning = {
          ...settings.reasoning,
          label: "Low",
          level,
          canIncrease: false,
          canDecrease: true,
        };
        return settings;
      },
    },
    run: async (_socketPath, action, args) => {
      calls.push(["helper", action, args]);
      return {
        ok: true,
        foreground: false,
        controls: { "model-picker": false, reasoning: false },
        agents: [{ id: FOCUSED_AGENT_ID, label: "Focused task", focused: true }],
        reasoning: { modelLabel: settings.model.label, level: settings.reasoning.level },
      };
    },
  });

  const status = await controller.status();
  assert.deepEqual(status.binding, { state: "confirmed", contextId: FOCUSED_AGENT_ID });
  assert.equal(status.controls.model, true);
  assert.equal(status.controls.reasoning, true);
  await controller.selectModel("gpt-luna", TARGET, effectBoundary(calls));
  await controller.adjustReasoning(1, TARGET, effectBoundary(calls));

  assert.deepEqual(calls, [
    ["helper", "status", []],
    ["effect", "commit"],
    ["app-server", "model", "gpt-luna"],
    ["effect", "commit"],
    ["app-server", "reasoning", "low"],
  ]);
});

test("keeps task B target-bound while task A remains the only confirmed desktop focus", async () => {
  const otherAgentId = agentIdForThread("thread-2");
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      target: TARGET,
      async resolveVisibleAgents() {
        return [{ id: otherAgentId, label: "Other task", focused: true }];
      },
      async settings() {
        return {
          target: TARGET,
          model: { available: true },
          mode: { available: false },
          reasoning: { available: true },
        };
      },
    },
    run: async () => ({
      ok: true,
      controls: { "model-picker": true, reasoning: true },
      identity: { mutationToken: "desktop-0123456789abcdef01234567" },
      agents: [{ id: otherAgentId, label: "Other task", focused: true }],
    }),
  });

  const status = await controller.status();

  assert.deepEqual(status.binding, { state: "reconciling", contextId: otherAgentId });
  assert.deepEqual(status.target, TARGET);
  assert.equal(status.agents.find(({ focused }) => focused)?.id, otherAgentId);
  assert.equal(status.controls.model, true);
  assert.equal(status.controls.reasoning, true);
  assert.equal(status.controls.approve, false);
  assert.equal(status.controls["mode-cycle"], false);
  const effects = effectBoundary();
  await assert.rejects(
    () => controller.press("approve", effects),
    /not confirmed as the visible desktop focus/i,
  );
  assert.equal(effects.crossed, false);
});

test("marks failed exact app-server evidence stale and disables target-bound settings", async () => {
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      target: TARGET,
      async resolveVisibleAgents() {
        return [{ id: FOCUSED_AGENT_ID, label: "Focused task", focused: true }];
      },
      async settings() {
        return {
          target: TARGET,
          fresh: false,
          model: { available: true, id: "gpt-sol", label: "Sol", options: [] },
          mode: { available: false, id: "default", label: "Default", options: [] },
          reasoning: { available: true, label: "High", level: "high" },
        };
      },
    },
    run: async () => ({
      ok: true,
      controls: { approve: true, reasoning: true },
      agents: [{ id: FOCUSED_AGENT_ID, label: "Focused task", focused: true }],
    }),
  });

  const status = await controller.status();

  assert.equal(status.sources.appServer.fresh, false);
  assert.equal(status.controls.model, false);
  assert.equal(status.controls.reasoning, false);
  assert.equal(status.model.available, false);
  assert.equal(status.reasoning.available, false);
  assert.equal(status.controls.approve, true);
});

test("advertises bound app-server settings without desktop controls or identity", async () => {
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      target: TARGET,
      async resolveVisibleAgents() {
        return [{ id: FOCUSED_AGENT_ID, label: "Focused task", focused: true }];
      },
      async settings() {
        return {
          target: TARGET,
          model: { available: true, id: "gpt-sol", label: "Sol", options: [] },
          mode: { available: false, id: "default", label: "Default", options: [] },
          reasoning: {
            available: true,
            label: "High",
            level: "high",
            canIncrease: true,
            canDecrease: true,
          },
        };
      },
    },
    run: async () => ({
      ok: true,
      taskState: "idle",
      controls: { "model-picker": false, reasoning: false, stop: false },
      agents: [{ id: FOCUSED_AGENT_ID, label: "Focused task", focused: true }],
    }),
  });

  const status = await controller.status();

  assert.deepEqual(status.binding, { state: "confirmed", contextId: FOCUSED_AGENT_ID });
  assert.equal(status.controls.model, true);
  assert.equal(status.controls.reasoning, true);
  assert.equal(status.controls["mode-cycle"], false);
  assert.equal(status.model.available, true);
  assert.equal(status.reasoning.available, true);
  assert.equal(status.mode.available, false);
});

test("keeps bound model and reasoning available when the Host is unavailable", async () => {
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      target: TARGET,
      freshness: { state: "fresh" },
      async resolveVisibleAgents() { return []; },
      async settings() {
        return {
          target: TARGET,
          model: { available: true, id: "gpt-sol", label: "Sol", options: [] },
          mode: { available: false, id: "default", label: "Default", options: [] },
          reasoning: {
            available: true,
            label: "High",
            level: "high",
            canIncrease: true,
            canDecrease: true,
          },
        };
      },
    },
    run: async () => { throw new Error("Host unavailable"); },
  });

  const status = await controller.status();

  assert.deepEqual(status.target, TARGET);
  assert.equal(status.sources.appServer.fresh, true);
  assert.equal(status.sources.desktopUI.fresh, false);
  assert.equal(status.controls.model, true);
  assert.equal(status.controls.reasoning, true);
  assert.equal(status.model.available, true);
  assert.equal(status.reasoning.available, true);
  assert.equal(status.controls["mode-cycle"], false);
  assert.equal(status.voice.available, false);
  assert.match(status.message, /Host unavailable/i);
});

test("rejects mode writes before crossing an effect boundary", async () => {
  const calls = [];
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: { target: TARGET },
    run: async () => { calls.push(["helper"]); return { ok: true }; },
  });
  const effects = effectBoundary(calls);

  await assert.rejects(() => controller.selectMode("plan", effects), /disabled by protocol v12/i);

  assert.deepEqual(calls, []);
  assert.equal(effects.crossed, false);
});

test("reports an unbound desktop when no visible Agent is focused", async () => {
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      target: null,
      async resolveVisibleAgents() { return []; },
      async settings() {
        return {
          target: null,
          model: { available: false },
          mode: { available: false },
          reasoning: { available: false },
        };
      },
    },
    run: async () => ({ ok: true, controls: {}, agents: [] }),
  });

  const status = await controller.status();

  assert.deepEqual(status.binding, { state: "unbound", contextId: null });
});

test("rejects stale model IDs before any prefix-colliding desktop option can be pressed", async () => {
  const calls = [];
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      target: TARGET,
      async resolveVisibleAgents() {
        return [{ id: FOCUSED_AGENT_ID, label: "Focused task", focused: true }];
      },
      async settings() {
        return {
          target: TARGET,
          model: {
            available: true,
            id: "gpt-solar",
            label: "Solar",
            options: [{ id: "gpt-solar", label: "Solar", selected: true }],
          },
          reasoning: { available: true, label: "Low", level: "low" },
        };
      },
      async validateModel({ target }) {
        assert.deepEqual(target, TARGET);
        throw new Error("The requested Codex model is unavailable or stale.");
      },
    },
    run: async (_socketPath, action, args) => {
      calls.push([action, args]);
      return {
        ok: true,
        taskState: "idle",
        controls: { "model-picker": true, reasoning: true },
        identity: { mutationToken: "desktop-0123456789abcdef01234567" },
        agents: [{ id: FOCUSED_AGENT_ID, label: "Focused task", focused: true }],
        reasoning: { modelLabel: "Solar", level: "low" },
      };
    },
  });

  const effects = effectBoundary(calls);
  await assert.rejects(() => controller.selectModel("gpt-sol", TARGET, effects), /unavailable or stale/i);
  assert.deepEqual(calls, []);
  assert.equal(effects.crossed, false);
});

test("selects reasoning through app-server while the desktop task is running", async () => {
  const calls = [];
  const settings = {
    target: TARGET,
    model: { available: true, id: "gpt-sol", label: "Sol", options: [] },
    mode: { available: false, id: "default", label: "Default", options: [] },
    reasoning: {
      available: true,
      label: "High",
      level: "high",
      canIncrease: true,
      canDecrease: true,
    },
  };
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      target: TARGET,
      async resolveVisibleAgents() {
        return [{ id: FOCUSED_AGENT_ID, label: "Focused task", focused: true }];
      },
      async settings() { return settings; },
      hasReasoningLevel() { return true; },
      requireTarget(target) { assert.deepEqual(target, TARGET); },
      async selectReasoning({ level, target, effects }) {
        assert.deepEqual(target, TARGET);
        await effects.commit(async () => { calls.push(["app-server", "reasoning", level]); });
        settings.reasoning = { ...settings.reasoning, level, label: "Extra high" };
        return settings;
      },
    },
    run: async (_socketPath, action, args) => {
      calls.push([action, args]);
      return {
        ok: true,
        taskState: "executing",
        controls: { stop: true, "model-picker": false, reasoning: false },
        agents: [{ id: FOCUSED_AGENT_ID, label: "Focused task", focused: true }],
        reasoning: { modelLabel: "Sol", level: "high" },
      };
    },
  });

  const exactEffects = effectBoundary(calls);
  await controller.selectReasoning("xhigh", TARGET, exactEffects);
  assert.deepEqual(calls, [
    ["effect", "commit"],
    ["app-server", "reasoning", "xhigh"],
  ]);
  assert.equal(exactEffects.crossed, true);
});

test("rejects a stale TargetRef before crossing the settings boundary", async () => {
  const calls = [];
  const settings = {
    target: TARGET,
    model: {
      available: true,
      id: "gpt-sol",
      label: "Sol",
      options: [{ id: "gpt-sol", label: "Sol", selected: true }],
    },
    reasoning: { available: true, label: "High", level: "high" },
  };
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      target: TARGET,
      async validateModel({ target }) {
        if (target.bindingEpoch !== TARGET.bindingEpoch) {
          throw new Error("The bound Codex target changed at bindingEpoch before its settings mutation.");
        }
        return settings.model.options[0];
      },
      async selectModel() { calls.push(["catalog", "selectModel"]); return settings; },
    },
    run: async () => { calls.push(["helper"]); return { ok: true }; },
  });
  const effects = effectBoundary(calls);
  const staleTarget = { ...TARGET, bindingEpoch: TARGET.bindingEpoch + 1 };

  await assert.rejects(
    () => controller.selectModel("gpt-sol", staleTarget, effects),
    /changed at bindingEpoch/i,
  );

  assert.equal(effects.crossed, false);
  assert.deepEqual(calls, []);
});

test("keeps bound app-server settings mutable without desktop controls or a desktop token", async () => {
  const settings = {
    target: TARGET,
    model: { available: true, id: "gpt-sol", label: "Sol", options: [] },
    reasoning: { available: true, label: "Medium", level: "medium", canIncrease: true, canDecrease: true },
  };
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      target: TARGET,
      async resolveVisibleAgents() {
        return [{ id: FOCUSED_AGENT_ID, label: "Focused task", focused: true }];
      },
      async settings() { return settings; },
    },
    run: async () => ({
      ok: true,
      foreground: false,
      controls: { "model-picker": false, reasoning: false },
      agents: [{ id: FOCUSED_AGENT_ID, label: "Focused task", focused: true }],
      reasoning: { modelLabel: "Sol", level: "medium" },
    }),
  });

  const status = await controller.status();

  assert.equal(status.controls.model, true);
  assert.equal(status.controls.reasoning, true);
  assert.equal(status.model.available, true);
  assert.equal(status.reasoning.available, true);
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

test("binds an exact semantic task without requiring desktop focus", async () => {
  const calls = [];
  const targetId = "agent-111111111111111111111111";
  const target = { ...TARGET, agentId: targetId, threadId: "019f2ce2-e042-7ab0-a73d-9fa41d58e210" };
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      async bindThread(threadId) {
        calls.push(["bindThread", threadId]);
        return { agentId: targetId, target };
      },
      async resolveVisibleAgents() {
        return [{ id: targetId, label: "Bound task", focused: true }];
      },
    },
    run: async (socketPath, action, args, input = "") => {
      calls.push([socketPath, action, args, input]);
      return { ok: true, controls: {}, agents: [] };
    },
  });

  const bound = await controller.bindThread(
    "019f2ce2-e042-7ab0-a73d-9fa41d58e210",
    effectBoundary(calls),
  );
  assert.deepEqual(bound.target, target);
  assert.deepEqual(calls, [
    ["effect", "commit"],
    ["bindThread", "019f2ce2-e042-7ab0-a73d-9fa41d58e210"],
  ]);
  const lifecycle = controller.applyLifecycleHook("Stop", {});
  assert.equal(lifecycle.accepted, false);
  assert.deepEqual(await lifecycle.response, {});
});

test("selects a controller Agent without opening or polling the desktop focus", async () => {
  const calls = [];
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      async selectAgent(agentId, effects) {
        calls.push(["selectAgent", agentId]);
        await effects.commit(async () => { calls.push(["bind"]); });
        return { agentId, target: TARGET };
      },
    },
    run: async () => { throw new Error("desktop helper must not run"); },
  });

  const selected = await controller.selectAgent(FOCUSED_AGENT_ID, effectBoundary(calls));

  assert.deepEqual(selected.target, TARGET);
  assert.deepEqual(calls, [
    ["selectAgent", FOCUSED_AGENT_ID],
    ["effect", "commit"],
    ["bind"],
  ]);
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
      return { ok: true, message: "opened", agentId, target: TARGET };
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

test("holds a following desktop action until the TargetRef focus lease is confirmed", async () => {
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
        return { agentId, target: TARGET };
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

test("keeps the selected control target distinct from the visible desktop binding", async () => {
  const calls = [];
  const currentId = "agent-111111111111111111111111";
  const targetId = "agent-222222222222222222222222";
  const target = { ...TARGET, agentId: targetId, threadId: "thread-b" };
  let boundTarget = null;
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    focusPollAttempts: 1,
    threadCatalog: {
      get target() { return boundTarget; },
      async focusAgent(agentId) {
        calls.push(["open", agentId]);
        boundTarget = target;
        return { agentId, target };
      },
      async resolveVisibleAgents() {
        calls.push(["observe"]);
        return [
          { id: currentId, label: "Task A", state: "idle", focused: true },
          { id: targetId, label: "Task B", state: "idle", focused: false },
        ];
      },
      async settings() {
        return {
          fresh: true,
          target,
          model: { available: true, id: "gpt-sol", label: "Sol", options: [] },
          mode: { available: false },
          reasoning: { available: true, level: "high", label: "High" },
        };
      },
    },
    run: async () => {
      calls.push(["helper"]);
      return { ok: true, controls: { approve: true }, agents: [] };
    },
  });

  await assert.rejects(() => controller.focusAgent(targetId), /focus timeout/i);
  const status = await controller.status();

  assert.deepEqual(status.target, target);
  assert.deepEqual(status.binding, { state: "reconciling", contextId: currentId });
  assert.equal(status.agents.find(({ focused }) => focused)?.id, targetId);
  assert.equal(status.controls.approve, false);
  assert.equal(status.controls.model, true);
  assert.equal(status.controls.reasoning, true);
});

test("rechecks a revoked credential after the desktop queue and focus lease unblock", async () => {
  const calls = [];
  const currentId = "agent-111111111111111111111111";
  const targetId = "agent-222222222222222222222222";
  let targetFocused = false;
  const focusWait = Promise.withResolvers();
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    focusPollAttempts: 2,
    wait: async () => focusWait.promise,
    threadCatalog: {
      async focusAgent(agentId, effects) {
        await effects.commit(async () => { calls.push(["open", agentId]); });
        return { agentId, target: TARGET };
      },
      async resolveVisibleAgents() {
        return [
          { id: currentId, label: "Task A", state: "idle", focused: !targetFocused },
          { id: targetId, label: "Task B", state: "idle", focused: targetFocused },
        ];
      },
    },
    run: async (_socketPath, action) => {
      calls.push(["helper", action]);
      return { ok: true, controls: {}, agents: [] };
    },
  });

  const focus = controller.focusAgent(targetId, new EffectBoundary());
  await new Promise((resolve) => setImmediate(resolve));
  let valid = true;
  const actionEffects = new EffectBoundary(() => valid);
  const action = controller.press("new-task", actionEffects);

  valid = false;
  targetFocused = true;
  focusWait.resolve();

  await focus;
  await assert.rejects(
    action,
    (error) => error.code === "credential_revoked",
  );
  assert.equal(actionEffects.crossed, false);
  assert.equal(calls.some(([kind, actionName]) => kind === "helper" && actionName === "control"), false);
});

test("propagates native catalog focus failure without Host fallback", async () => {
  const targetId = "agent-222222222222222222222222";
  let hostCalls = 0;
  const controller = new Desktop({
    socketPath: "/tmp/vibe-pocket-test.sock",
    threadCatalog: {
      async focusAgent() { throw new Error("temporary app-server failure"); },
    },
    run: async () => { hostCalls += 1; return { ok: true, controls: {}, agents: [] }; },
  });

  await assert.rejects(() => controller.focusAgent(targetId), /temporary app-server failure/i);
  assert.equal(hostCalls, 0);
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
      get freshness() {
        return {
          state: failCatalog ? "stale" : "fresh",
          error: failCatalog ? "temporary app-server failure" : null,
        };
      },
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

  const initial = await controller.status();
  assert.deepEqual(initial.agents, agents);
  assert.deepEqual(initial.tasks, { availability: "fresh", message: null });
  failCatalog = true;
  const recovered = await controller.status();

  assert.deepEqual(recovered.agents, agents.map((agent) => ({
    ...agent,
    focused: false,
    freshness: "stale",
    actionable: false,
  })));
  assert.deepEqual(recovered.tasks, { availability: "stale", message: "temporary app-server failure" });
  assert.equal(recovered.controls["focus-agent"], false);
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
