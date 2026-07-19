import assert from "node:assert/strict";
import test from "node:test";

import { Hybrid } from "../../src/control/hybrid.mjs";

class FakeTaskController {
  calls = [];
  snapshot = {
    taskState: "idle",
    controls: { stop: false, reasoning: true, workflow: true, "clear-input": false },
    voice: { available: true, active: false },
    userInput: null,
  };

  async status() { this.calls.push(["status"]); return structuredClone(this.snapshot); }
  async attach() { this.calls.push(["attach"]); return { message: "attached" }; }
  async bindThread(id) { this.calls.push(["bindThread", id]); return { message: "bound" }; }
  async applyLifecycleHook(event, payload) { this.calls.push(["hook", event, payload]); return { accepted: true }; }
  async press(control) { this.calls.push(["press", control]); return { message: control }; }
  async setVoice(active) { this.calls.push(["setVoice", active]); }
  async navigate(direction) { this.calls.push(["navigate", direction]); }
  async cycleMode() { this.calls.push(["cycleMode"]); return { message: "mode" }; }
  async cycleAccess() { this.calls.push(["cycleAccess"]); return { message: "access" }; }
  async clearInput() { this.calls.push(["clearInput"]); return { message: "clear" }; }
  async focusAgent(id) { this.calls.push(["focusAgent", id]); }
  async adjustReasoning(delta) { this.calls.push(["adjustReasoning", delta]); return { message: "reasoning" }; }
  async workflow(prompt) { this.calls.push(["workflow", prompt]); }
  async dispose() { this.calls.push(["dispose"]); }
}

class FakeVisibleController {
  calls = [];
  voiceActive = false;
  snapshot = {
    taskState: "idle",
    message: "Ready to control the visible Codex window.",
    controls: {
      approve: true,
      reject: true,
      stop: false,
      "new-task": true,
      "clear-input": true,
      "plan-mode": true,
      "model-picker": true,
      "mode-cycle": true,
      navigate: true,
      reasoning: true,
    },
    mode: { available: true, label: "Full access" },
    reasoning: { available: true, label: "Medium" },
  };
  async status() { this.calls.push(["status"]); return structuredClone(this.snapshot); }
  async activate() { this.calls.push(["activate"]); }
  async press(control) { this.calls.push(["press", control]); return { message: control }; }
  async setVoice(active) { this.calls.push(["setVoice", active]); this.voiceActive = active; return { message: "voice" }; }
  async setDictationDraft(text) { this.calls.push(["setDictationDraft", text]); return { message: "draft" }; }
  async navigate(direction) { this.calls.push(["navigate", direction]); }
  async cycleMode() { this.calls.push(["cycleMode"]); }
  async cycleAccess() { this.calls.push(["cycleAccess"]); }
  async openModel() { this.calls.push(["openModel"]); }
  async deleteBackward() { this.calls.push(["deleteBackward"]); }
  async adjustReasoning(delta) { this.calls.push(["adjustReasoning", delta]); }
  async clearInput() { this.calls.push(["clearInput"]); }
  async workflow(prompt) { this.calls.push(["workflow", prompt]); }
}

function makeHybrid() {
  const task = new FakeTaskController();
  const visible = new FakeVisibleController();
  return { task, visible, controller: new Hybrid({ taskController: task, accessibilityController: visible }) };
}

test("advertises controls from the visible Codex state", async () => {
  const { controller, visible } = makeHybrid();
  const status = await controller.status();

  assert.equal(status.controls.approve, true);
  assert.equal(status.controls.reject, true);
  assert.equal(status.controls.stop, false);
  assert.equal(status.controls["clear-input"], true);
  assert.equal(status.controls["model-picker"], true);
  assert.equal(status.controls.reasoning, true);
  assert.equal(status.mode.label, "Codex");
  assert.equal(status.access.label, "Full access");
  assert.equal(status.reasoning.label, "Medium");
  assert.match(status.message, /visible Codex window/);
  assert.deepEqual(visible.calls, [["status"]]);
});

test("routes visible composer and navigation actions through Accessibility", async () => {
  const { controller, task, visible } = makeHybrid();
  task.snapshot.controls["clear-input"] = true;

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
  assert.equal(task.calls.some((call) => call[0] === "cycleMode"), false);
  assert.ok(task.calls.some((call) => call[0] === "setVoice" && call[1] === false));
});

test("routes structured questions and stored drafts through app-server", async () => {
  const { controller, task, visible } = makeHybrid();
  task.snapshot.userInput = { header: "Choice" };
  task.snapshot.controls["clear-input"] = true;

  await controller.navigate("down");
  await controller.clearInput();

  assert.deepEqual(visible.calls, []);
  assert.ok(task.calls.some((call) => call[0] === "navigate" && call[1] === "down"));
  assert.ok(task.calls.some((call) => call[0] === "clearInput"));
});

test("keeps visible lifecycle actions on the semantic Accessibility controller", async () => {
  const { controller, task, visible } = makeHybrid();

  await controller.attach();
  await controller.press("new-task");
  await controller.adjustReasoning(1);
  await controller.cycleAccess();
  await controller.focusAgent("agent-a");
  await controller.workflow("Review this change.");

  assert.deepEqual(visible.calls, [
    ["activate"],
    ["press", "new-task"],
    ["adjustReasoning", 1],
    ["cycleAccess"],
    ["workflow", "Review this change."],
  ]);
  assert.equal(task.calls.some((call) => call[0] === "press" && call[1] === "new-task"), false);
  assert.equal(task.calls.some((call) => call[0] === "adjustReasoning"), false);
  assert.equal(task.calls.some((call) => call[0] === "cycleAccess"), false);
  assert.equal(task.calls.some((call) => call[0] === "workflow"), false);
  assert.equal(task.calls.filter((call) => call[0] === "attach").length, 1);
});

test("resolves waiting approvals through app-server and stops the visible turn", async () => {
  const { controller, task, visible } = makeHybrid();
  task.snapshot.taskState = "waiting";
  task.snapshot.controls.stop = true;

  await controller.press("approve");
  await controller.press("reject");
  await controller.press("stop");

  assert.deepEqual(visible.calls, [["press", "stop"]]);
  assert.ok(task.calls.some((call) => call[0] === "press" && call[1] === "approve"));
  assert.ok(task.calls.some((call) => call[0] === "press" && call[1] === "reject"));
  assert.equal(task.calls.some((call) => call[0] === "press" && call[1] === "stop"), false);
});
