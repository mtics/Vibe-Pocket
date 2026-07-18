import assert from "node:assert/strict";
import test from "node:test";

import { createDefaultControllerProfile } from "../src/controller-profile.mjs";
import { DesktopCodexService } from "../src/desktop-codex-service.mjs";

class FakeEvents {
  published = [];
  publish(type, data) { this.published.push({ type, data }); }
}

class FakeDesktop {
  calls = [];
  taskState = "waiting";
  voice = { available: true, active: false };
  agents = [
    { id: "agent-111111111111111111111111", label: "Turing", state: "thinking", focused: true },
    { id: "agent-222222222222222222222222", label: "Dalton", state: "unread", focused: false },
  ];

  async status() {
    return {
      available: true,
      message: "Desktop task ready.",
      taskState: this.taskState,
      controls: {
        voice: true,
        stop: true,
        "new-task": true,
        approve: true,
        reject: true,
        "clear-input": true,
        "focus-agent": true,
        "mode-cycle": true,
        "access-cycle": true,
        navigate: true,
        reasoning: true,
        workflow: true,
      },
      agents: this.agents,
      voice: this.voice,
      mode: { available: true, label: "Codex" },
      access: { available: true, label: "Workspace" },
      reasoning: { available: true, label: "High" },
    };
  }

  async attach() { this.calls.push(["attach"]); }
  async press(control) { this.calls.push(["press", control]); }
  async setVoice(active) {
    this.calls.push(["setVoice", active]);
    this.voice = { available: true, active };
  }
  async setDictationDraft(text) { this.calls.push(["setDictationDraft", text]); }
  async navigate(direction) { this.calls.push(["navigate", direction]); }
  async cycleMode() { this.calls.push(["cycleMode"]); }
  async cycleAccess() { this.calls.push(["cycleAccess"]); }
  async clearInput() { this.calls.push(["clearInput"]); }
  async focusAgent(index) { this.calls.push(["focusAgent", index]); }
  async adjustReasoning(delta) { this.calls.push(["adjustReasoning", delta]); }
  async workflow(prompt) { this.calls.push(["workflow", prompt]); }
  async applyLifecycleHook(event, payload) {
    this.calls.push(["applyLifecycleHook", event, payload]);
    this.taskState = event === "Stop" ? "complete" : "thinking";
    return { accepted: true, response: Promise.resolve({ event }) };
  }
}

class MemoryProfileStore {
  constructor(profile = createDefaultControllerProfile()) {
    this.profile = structuredClone(profile);
    this.saves = 0;
  }

  async load() { return structuredClone(this.profile); }
  async save(profile) {
    this.profile = structuredClone(profile);
    this.saves += 1;
    return structuredClone(this.profile);
  }
}

function makeService(desktop = new FakeDesktop(), events = new FakeEvents(), options = {}) {
  return new DesktopCodexService({
    desktop,
    events,
    workspaces: { research: "/Users/lizhw/Research" },
    pollIntervalMs: 0,
    ...options,
  });
}

test("publishes a capability-driven Codex Micro controller snapshot", async () => {
  const service = makeService();
  await service.start();

  const snapshot = await service.snapshot();
  assert.equal(snapshot.focusSessionId, "vibe-pocket-codex");
  assert.equal(snapshot.controller.profile.layers.length, 6);
  assert.equal(snapshot.controller.profile.inputs.length, 20);
  assert.deepEqual(snapshot.controller.gestures.map(({ id }) => id), ["tap", "double_tap", "hold"]);
  assert.ok(snapshot.controller.actionCatalog.some(({ id }) => id === "workflow_debug"));
  assert.equal(snapshot.controller.activeLayerId, "layer-1");
  assert.equal(snapshot.controller.taskState, "waiting");
  assert.deepEqual(snapshot.controller.agents, [
    { id: "agent-111111111111111111111111", label: "Turing", state: "thinking", focused: true },
    { id: "agent-222222222222222222222222", label: "Dalton", state: "unread", focused: false },
  ]);
  assert.equal(snapshot.controller.focusedAgentId, "agent-111111111111111111111111");
  assert.deepEqual(snapshot.controller.voice, { available: true, active: false });
  assert.equal(snapshot.controller.mode.label, "Codex");
  assert.equal(snapshot.controller.access.label, "Workspace");
  assert.equal(snapshot.controller.reasoning.label, "High");
  assert.equal(snapshot.controls.reasoning, true);
});

test("publishes hook-driven desktop state before returning the hook response", async () => {
  const desktop = new FakeDesktop();
  const events = new FakeEvents();
  const service = makeService(desktop, events);
  await service.start();
  const initialRevision = (await service.snapshot()).revision;

  const response = await service.codexHook("UserPromptSubmit", {
    hook_event_name: "UserPromptSubmit",
    session_id: "019f2ce2-e042-7ab0-a73d-9fa41d58e210",
  });

  assert.deepEqual(response, { event: "UserPromptSubmit" });
  assert.deepEqual(desktop.calls, [["applyLifecycleHook", "UserPromptSubmit", {
    hook_event_name: "UserPromptSubmit",
    session_id: "019f2ce2-e042-7ab0-a73d-9fa41d58e210",
  }]]);
  assert.notEqual((await service.snapshot()).revision, initialRevision);
});

test("routes default-layer keys, joystick, touch, and dial inputs", async () => {
  const desktop = new FakeDesktop();
  const service = makeService(desktop);
  await service.start();

  const inputs = [
    "key_accept",
    "key_reject",
    "key_voice",
    "key_new_task",
    "key_stop",
    "key_mode",
    "key_clear",
    "key_up",
    "touch",
    "joystick_up",
    "dial_cw",
  ];
  for (const [index, inputId] of inputs.entries()) {
    await service.command({ kind: "binding", inputId }, `binding-${index}`);
  }

  assert.deepEqual(desktop.calls.slice(0, 9), [
    ["press", "approve"],
    ["press", "reject"],
    ["setVoice", true],
    ["press", "new-task"],
    ["press", "stop"],
    ["cycleMode"],
    ["clearInput"],
    ["navigate", "up"],
    ["focusAgent", "agent-222222222222222222222222"],
  ]);
  assert.equal(desktop.calls[9][0], "workflow");
  assert.match(desktop.calls[9][1], /Review the current change/);
  assert.deepEqual(desktop.calls[10], ["adjustReasoning", 1]);
});

test("focuses one of the six explicit Codex agent slots", async () => {
  const desktop = new FakeDesktop();
  const service = makeService(desktop);
  await service.start();

  await service.command({ kind: "focus_agent", agentId: "agent-111111111111111111111111" }, "focus-agent-1");
  assert.deepEqual(desktop.calls, [["focusAgent", "agent-111111111111111111111111"]]);
  assert.equal((await service.snapshot()).controller.focusedAgentIndex, 0);
  await assert.rejects(
    () => service.command({ kind: "focus_agent", agentId: "agent-666666666666666666666666" }, "focus-agent-6"),
    (error) => error.code === "agent_unavailable",
  );
});

test("serializes push-to-talk target states without losing release", async () => {
  const desktop = new FakeDesktop();
  const service = makeService(desktop);
  await service.start();

  await service.command({ kind: "voice_start" }, "voice-down");
  await service.command({ kind: "voice_stop" }, "voice-up");

  assert.deepEqual(desktop.calls, [["setVoice", true], ["setVoice", false]]);
  assert.equal((await service.snapshot()).controller.voice.active, false);
});

test("routes a bounded phone dictation result into the controller draft", async () => {
  const desktop = new FakeDesktop();
  const service = makeService(desktop);
  await service.start();

  await service.command({ kind: "dictation_result", text: "Review the current change." }, "dictation-1");

  assert.deepEqual(desktop.calls, [["setDictationDraft", "Review the current change."]]);
  await assert.rejects(
    () => service.command({ kind: "dictation_result", text: "" }, "dictation-empty"),
    (error) => error.status === 400,
  );
});

test("switches layers and rejects inputs that are not mapped on that layer", async () => {
  const service = makeService();
  await service.start();

  await service.command({ kind: "select_layer", layerId: "layer-2" }, "layer-2");
  assert.equal((await service.snapshot()).controller.activeLayerId, "layer-2");
  await assert.rejects(
    () => service.command({ kind: "binding", inputId: "key_voice" }, "layer-2-voice"),
    (error) => error.code === "unmapped_input",
  );
});

test("binds idempotency keys to one request body", async () => {
  const desktop = new FakeDesktop();
  const service = makeService(desktop);
  await service.start();

  const first = await service.command({ kind: "voice" }, "same-key");
  const replay = await service.command({ kind: "voice" }, "same-key");
  assert.deepEqual(replay, first);
  assert.deepEqual(desktop.calls, [["setVoice", true]]);
  await assert.rejects(
    () => service.command({ kind: "stop" }, "same-key"),
    (error) => error.code === "idempotency_key_reused",
  );
});

test("returns a precise error when a caller selects an imaginary desktop session", async () => {
  const service = makeService();
  await service.start();

  await assert.rejects(
    () => service.command({ kind: "focus", sessionId: "history-thread-id" }, "focus-foreign"),
    (error) => error.code === "unknown_session",
  );
});

test("keeps controls and desktop state available when one action fails", async () => {
  class PendingControlDesktop extends FakeDesktop {
    async press() { throw new Error("The ChatGPT Codex Stop control is not currently visible."); }
  }

  const service = makeService(new PendingControlDesktop());
  await service.start();

  await assert.rejects(
    () => service.command({ kind: "stop" }, "stop-missing-control"),
    (error) => error.code === "desktop_action_failed",
  );
  const snapshot = await service.snapshot();
  assert.equal(snapshot.status.state, "ready");
  assert.equal(snapshot.sessions[0].state, "active");
  assert.equal(snapshot.controls.stop, true);
  assert.equal(snapshot.controller.taskState, "waiting");
});

test("polling publishes an event only when desktop state changes", async () => {
  const desktop = new FakeDesktop();
  const events = new FakeEvents();
  const service = makeService(desktop, events, { pollIntervalMs: 10 });
  await service.start();
  const initialEvents = events.published.length;

  await new Promise((resolve) => setTimeout(resolve, 25));
  assert.equal(events.published.length, initialEvents);
  desktop.taskState = "complete";
  await new Promise((resolve) => setTimeout(resolve, 25));
  assert.ok(events.published.length > initialEvents);
  assert.equal((await service.snapshot()).controller.taskState, "complete");
  await service.dispose();
});

test("slow polling remains single-flight instead of accumulating status calls", async () => {
  class SlowDesktop extends FakeDesktop {
    inFlight = 0;
    maxInFlight = 0;
    statusCalls = 0;

    async status() {
      this.statusCalls += 1;
      this.inFlight += 1;
      this.maxInFlight = Math.max(this.maxInFlight, this.inFlight);
      await new Promise((resolve) => setTimeout(resolve, 24));
      try {
        return await super.status();
      } finally {
        this.inFlight -= 1;
      }
    }
  }

  const desktop = new SlowDesktop();
  const service = makeService(desktop, new FakeEvents(), { pollIntervalMs: 5 });
  await service.start();
  await new Promise((resolve) => setTimeout(resolve, 58));
  await service.dispose();

  assert.equal(desktop.maxInFlight, 1);
  assert.ok(desktop.statusCalls <= 4, `expected bounded status calls, saw ${desktop.statusCalls}`);
});

test("updates and dispatches a selected layer gesture while preserving legacy tap dispatch", async () => {
  const desktop = new FakeDesktop();
  const service = makeService(desktop);
  await service.start();

  await service.command({
    kind: "update_binding",
    layerId: "layer-2",
    inputId: "key_voice",
    gesture: "double_tap",
    action: { type: "navigate", direction: "left" },
  }, "map-double-tap");
  await service.command({ kind: "select_layer", layerId: "layer-2" }, "select-custom-layer");
  await service.command({ kind: "binding", inputId: "key_voice", gesture: "double_tap" }, "dispatch-double-tap");
  assert.deepEqual(desktop.calls, [["navigate", "left"]]);

  await assert.rejects(
    () => service.command({ kind: "binding", inputId: "key_voice" }, "unmapped-legacy-tap"),
    (error) => error.code === "unmapped_input",
  );
});

test("persists layer renames and resets the complete profile", async () => {
  const store = new MemoryProfileStore();
  const first = makeService(new FakeDesktop(), new FakeEvents(), { profileStore: store });
  await first.start();
  await first.command({ kind: "rename_layer", layerId: "layer-2", name: "Research" }, "rename-layer");
  await first.command({
    kind: "update_binding",
    layerId: "layer-2",
    inputId: "touch",
    gesture: "hold",
    action: { type: "workflow", workflowId: "review-pr" },
  }, "add-hold-binding");
  await first.dispose();

  const restarted = makeService(new FakeDesktop(), new FakeEvents(), { profileStore: store });
  await restarted.start();
  let snapshot = await restarted.snapshot();
  assert.equal(snapshot.controller.profile.layers[1].name, "Research");
  assert.equal(snapshot.controller.profile.layers[1].bindings.touch.hold.workflowId, "review-pr");

  await restarted.command({ kind: "select_layer", layerId: "layer-2" }, "select-before-reset");
  await restarted.command({ kind: "reset_profile" }, "reset-profile");
  snapshot = await restarted.snapshot();
  assert.equal(snapshot.controller.activeLayerId, "layer-1");
  assert.equal(snapshot.controller.profile.layers[1].name, "Layer 2");
  assert.deepEqual(snapshot.controller.profile.layers[1].bindings, {});
  assert.equal(store.saves, 3);
});

test("persists workflow prompts and colors and dispatches semantic layer switching", async () => {
  const store = new MemoryProfileStore();
  const first = makeService(new FakeDesktop(), new FakeEvents(), { profileStore: store });
  await first.start();
  await first.command({
    kind: "update_workflow",
    workflowId: "debug",
    prompt: "Reproduce the issue, apply the smallest fix, and run targeted tests.",
  }, "workflow-prompt");
  await first.command({
    kind: "update_layer_color",
    layerId: "layer-2",
    color: "#28B4A0",
  }, "layer-color");
  await first.command({
    kind: "update_binding",
    layerId: "layer-1",
    inputId: "key_focus",
    action: { type: "select_layer", layerId: "layer-2" },
  }, "layer-binding");
  await first.dispose();

  const desktop = new FakeDesktop();
  const restarted = makeService(desktop, new FakeEvents(), { profileStore: store });
  await restarted.start();
  let snapshot = await restarted.snapshot();
  assert.equal(snapshot.controller.profile.layers[1].color, "#28B4A0");
  assert.match(snapshot.controller.profile.workflows.find(({ id }) => id === "debug").prompt, /smallest fix/);

  await restarted.command({ kind: "binding", inputId: "key_focus" }, "switch-with-binding");
  snapshot = await restarted.snapshot();
  assert.equal(snapshot.controller.activeLayerId, "layer-2");
  await restarted.command({ kind: "select_layer", layerId: "layer-1" }, "return-layer-1");
  await restarted.command({ kind: "binding", inputId: "joystick_down" }, "run-custom-debug");
  assert.deepEqual(desktop.calls.at(-1), [
    "workflow",
    "Reproduce the issue, apply the smallest fix, and run targeted tests.",
  ]);
});

test("rejects unsafe configuration commands before desktop dispatch or persistence", async () => {
  const desktop = new FakeDesktop();
  const store = new MemoryProfileStore();
  const service = makeService(desktop, new FakeEvents(), { profileStore: store });
  await service.start();

  const invalid = [
    { kind: "update_binding", layerId: "layer-1", inputId: "key_voice", action: { type: "workflow", workflowId: "private", prompt: "run this" } },
    { kind: "update_binding", layerId: "layer-1", inputId: "key_voice", action: { type: "raw_key", key: "return" } },
    { kind: "clear_binding", layerId: "layer-1", inputId: "key_voice", gesture: "triple_tap" },
    { kind: "rename_layer", layerId: "layer-1", name: "" },
    { kind: "reset_profile", prompt: "hidden payload" },
    { kind: "prompt", text: "arbitrary Codex prompt" },
  ];
  for (const [index, command] of invalid.entries()) {
    await assert.rejects(
      () => service.command(command, `unsafe-${index}`),
      (error) => error.status === 400,
    );
  }
  assert.equal(store.saves, 0);
  assert.deepEqual(desktop.calls, []);
});
