import assert from "node:assert/strict";
import test from "node:test";

import { HybridCodexController } from "../src/hybrid-codex-controller.mjs";

class FakeTaskController {
  calls = [];
  snapshot = {
    taskState: "idle",
    controls: { stop: false, reasoning: true, workflow: true },
    voice: { available: true, active: false },
    userInput: null,
  };

  async status() { this.calls.push(["status"]); return structuredClone(this.snapshot); }
  async attach() { this.calls.push(["attach"]); return { message: "attached" }; }
  async bindThread(id) { this.calls.push(["bindThread", id]); return { message: "bound" }; }
  async applyLifecycleHook(event, payload) { this.calls.push(["hook", event, payload]); return { accepted: true }; }
  async press(control) { this.calls.push(["press", control]); return { message: control }; }
  async setVoice(active) { this.calls.push(["setVoice", active]); }
  async cycleAccess() { this.calls.push(["cycleAccess"]); return { message: "access" }; }
  async focusAgent(id) { this.calls.push(["focusAgent", id]); }
  async adjustReasoning(delta) { this.calls.push(["adjustReasoning", delta]); return { message: "reasoning" }; }
  async workflow(prompt) { this.calls.push(["workflow", prompt]); }
  async dispose() { this.calls.push(["dispose"]); }
}

class FakeVisibleController {
  calls = [];
  voiceActive = false;
  async activate() { this.calls.push(["activate"]); }
  async press(control) { this.calls.push(["press", control]); return { message: control }; }
  async setVoice(active) { this.calls.push(["setVoice", active]); this.voiceActive = active; return { message: "voice" }; }
  async setDictationDraft(text) { this.calls.push(["setDictationDraft", text]); return { message: "draft" }; }
  async navigate(direction) { this.calls.push(["navigate", direction]); }
  async cycleMode() { this.calls.push(["cycleMode"]); }
  async clearInput() { this.calls.push(["clearInput"]); }
}

function makeHybrid() {
  const task = new FakeTaskController();
  const visible = new FakeVisibleController();
  return { task, visible, controller: new HybridCodexController({ taskController: task, accessibilityController: visible }) };
}

test("advertises visible-window controls independently of task state", async () => {
  const { controller } = makeHybrid();
  const status = await controller.status();

  assert.equal(status.controls.approve, true);
  assert.equal(status.controls.reject, true);
  assert.equal(status.controls.stop, true);
  assert.equal(status.controls["clear-input"], true);
  assert.equal(status.controls.reasoning, true);
  assert.match(status.message, /visible Codex window/);
});

test("routes composer and navigation actions through Accessibility", async () => {
  const { controller, task, visible } = makeHybrid();

  await controller.press("approve");
  await controller.press("reject");
  await controller.press("stop");
  await controller.navigate("right");
  await controller.cycleMode();
  await controller.clearInput();
  await controller.setDictationDraft("Visible draft");

  assert.deepEqual(visible.calls, [
    ["press", "approve"],
    ["press", "reject"],
    ["press", "stop"],
    ["navigate", "right"],
    ["cycleMode"],
    ["clearInput"],
    ["setDictationDraft", "Visible draft"],
  ]);
  assert.ok(task.calls.some((call) => call[0] === "setVoice" && call[1] === false));
});

test("keeps task lifecycle actions on app-server and refreshes the visible task", async () => {
  const { controller, task, visible } = makeHybrid();

  await controller.attach();
  await controller.press("new-task");
  await controller.adjustReasoning(1);
  await controller.cycleAccess();
  await controller.focusAgent("agent-a");
  await controller.workflow("Review this change.");

  assert.deepEqual(visible.calls, [["activate"], ["activate"]]);
  assert.ok(task.calls.some((call) => call[0] === "press" && call[1] === "new-task"));
  assert.ok(task.calls.some((call) => call[0] === "adjustReasoning" && call[1] === 1));
  assert.ok(task.calls.some((call) => call[0] === "cycleAccess"));
  assert.ok(task.calls.filter((call) => call[0] === "attach").length >= 3);
});

test("resolves waiting approvals and active turns through app-server", async () => {
  const { controller, task, visible } = makeHybrid();
  task.snapshot.taskState = "waiting";
  task.snapshot.controls.stop = true;

  await controller.press("approve");
  await controller.press("reject");
  await controller.press("stop");

  assert.deepEqual(visible.calls, []);
  assert.ok(task.calls.some((call) => call[0] === "press" && call[1] === "approve"));
  assert.ok(task.calls.some((call) => call[0] === "press" && call[1] === "reject"));
  assert.ok(task.calls.some((call) => call[0] === "press" && call[1] === "stop"));
});
