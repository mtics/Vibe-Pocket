import assert from "node:assert/strict";
import test from "node:test";

import { DesktopCodexService } from "../src/desktop-codex-service.mjs";

class FakeEvents {
  published = [];
  publish(type, data) { this.published.push({ type, data }); }
}

class FakeDesktop {
  calls = [];
  taskState = "waiting";

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
        navigate: true,
        reasoning: true,
        workflow: true,
      },
      agents: [{ label: "Turing", state: "executing" }],
      mode: { available: true, label: "Codex" },
      reasoning: { available: true, label: "High" },
    };
  }

  async attach() { this.calls.push(["attach"]); }
  async press(control) { this.calls.push(["press", control]); }
  async navigate(direction) { this.calls.push(["navigate", direction]); }
  async cycleMode() { this.calls.push(["cycleMode"]); }
  async clearInput() { this.calls.push(["clearInput"]); }
  async focusAgent(index) { this.calls.push(["focusAgent", index]); }
  async adjustReasoning(delta) { this.calls.push(["adjustReasoning", delta]); }
  async workflow(prompt) { this.calls.push(["workflow", prompt]); }
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
  assert.equal(snapshot.focusSessionId, "desktop-codex");
  assert.equal(snapshot.controller.profile.layers.length, 6);
  assert.equal(snapshot.controller.profile.inputs.length, 20);
  assert.equal(snapshot.controller.activeLayerId, "layer-1");
  assert.equal(snapshot.controller.taskState, "waiting");
  assert.deepEqual(snapshot.controller.agents, [{ label: "Turing", state: "executing" }]);
  assert.equal(snapshot.controller.mode.label, "Codex");
  assert.equal(snapshot.controller.reasoning.label, "High");
  assert.equal(snapshot.controls.reasoning, true);
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
    ["press", "voice"],
    ["press", "new-task"],
    ["press", "stop"],
    ["cycleMode"],
    ["clearInput"],
    ["navigate", "up"],
    ["focusAgent", 0],
  ]);
  assert.equal(desktop.calls[9][0], "workflow");
  assert.match(desktop.calls[9][1], /Review the current change/);
  assert.deepEqual(desktop.calls[10], ["adjustReasoning", 1]);
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
  assert.deepEqual(desktop.calls, [["press", "voice"]]);
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
