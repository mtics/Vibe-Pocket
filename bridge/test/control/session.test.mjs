import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { bindingFor, createDefault } from "../../src/profile/model.mjs";
import { Operations, persistOperations } from "../../src/control/operations.mjs";
import { Session } from "../../src/control/session.mjs";

class FakeEvents {
  published = [];
  publish(type, data) { this.published.push({ type, data }); }
}

class FakeDesktop {
  calls = [];
  taskState = "waiting";
  voice = { available: true, active: false };
  reasoning = {
    available: true,
    label: "High",
    modelLabel: "Codex",
    level: "high",
    canIncrease: true,
    canDecrease: true,
  };
  model = {
    available: true,
    id: "gpt-test",
    label: "Codex",
    options: [{ id: "gpt-test", label: "Codex", selected: true }],
  };
  agents = [
    { id: "agent-111111111111111111111111", label: "Turing", state: "thinking", focused: true },
    { id: "agent-222222222222222222222222", label: "Dalton", state: "unread", focused: false },
  ];

  async status() {
    return {
      available: true,
      foreground: true,
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
        "model-picker": true,
        model: true,
        "access-cycle": true,
        navigate: true,
        reasoning: true,
        workflow: true,
      },
      agents: this.agents,
      voice: this.voice,
      mode: { available: true, label: "Codex" },
      access: { available: true, label: "Workspace" },
      model: this.model,
      reasoning: this.reasoning,
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
  async openModel() { this.calls.push(["openModel"]); }
  async selectModel(modelId, effects) {
    return effects.commit(() => { this.calls.push(["selectModel", modelId]); });
  }
  async deleteBackward() { this.calls.push(["deleteBackward"]); }
  async clearInput() { this.calls.push(["clearInput"]); }
  async focusAgent(index) { this.calls.push(["focusAgent", index]); }
  async adjustReasoning(delta, effects) {
    return effects.commit(() => { this.calls.push(["adjustReasoning", delta]); });
  }
  async workflow(prompt) { this.calls.push(["workflow", prompt]); }
  async applyLifecycleHook(event, payload) {
    this.calls.push(["applyLifecycleHook", event, payload]);
    this.taskState = event === "Stop" ? "complete" : "thinking";
    return { accepted: true, response: Promise.resolve({ event }) };
  }
}

class MemoryProfileStore {
  constructor(profile = createDefault()) {
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
  return new Session({
    desktop,
    events,
    workspaces: { research: "/Users/lizhw/Research" },
    pollIntervalMs: 0,
    ...options,
  });
}

function bindingCommand(inputId, {
  gesture = "tap",
  layerId = "layer-1",
  profile = createDefault(),
  action = bindingFor(profile, layerId, inputId, gesture) ?? { type: "voice" },
} = {}) {
  return { kind: "binding", inputId, gesture, layerId, action };
}

async function temporaryOperationPath(t) {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-session-operations-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  return join(root, "operations.json");
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
  assert.equal(snapshot.controller.foreground, true);
  assert.deepEqual(snapshot.controller.voice, { available: true, active: false });
  assert.equal(snapshot.controller.mode.label, "Codex");
  assert.equal(snapshot.controller.access.label, "Workspace");
  assert.equal(snapshot.controller.reasoning.label, "High");
  assert.equal(snapshot.controller.model.label, "Codex");
  assert.equal(snapshot.controller.model.id, "gpt-test");
  assert.equal(snapshot.controller.reasoning.level, "high");
  assert.equal(snapshot.controller.reasoning.canIncrease, true);
  assert.equal(snapshot.controller.reasoning.canDecrease, true);
  assert.equal(snapshot.controls.reasoning, true);
  assert.equal(snapshot.controls["model-picker"], true);
  assert.equal(snapshot.controls.model, true);
});

test("keeps reasoning adjustable upward at the minimum level", async () => {
  const desktop = new FakeDesktop();
  desktop.reasoning = {
    available: true,
    label: "5.6 Sol 最小",
    modelLabel: "5.6 Sol",
    level: "minimal",
    canIncrease: true,
    canDecrease: false,
  };
  desktop.model = {
    available: true,
    id: "gpt-5.6-sol",
    label: "5.6 Sol",
    options: [{ id: "gpt-5.6-sol", label: "5.6 Sol", selected: true }],
  };
  const service = makeService(desktop);
  await service.start();

  const snapshot = await service.snapshot();
  assert.equal(snapshot.controls.reasoning, true);
  assert.equal(snapshot.controller.model.label, "5.6 Sol");
  assert.deepEqual(snapshot.controller.reasoning, {
    available: true,
    label: "5.6 Sol 最小",
    level: "minimal",
    canIncrease: true,
    canDecrease: false,
  });
});

test("keeps both reasoning directions available when the host sees an unknown label", async () => {
  const desktop = new FakeDesktop();
  desktop.reasoning = {
    available: true,
    label: "5.7 Preview",
    modelLabel: "",
    level: "",
    canIncrease: true,
    canDecrease: true,
  };
  const service = makeService(desktop);
  await service.start();

  const reasoning = (await service.snapshot()).controller.reasoning;
  assert.equal(reasoning.available, true);
  assert.equal(reasoning.level, null);
  assert.equal(reasoning.canIncrease, true);
  assert.equal(reasoning.canDecrease, true);
});

test("keeps more than six visible agents up to the bounded controller limit", async () => {
  const desktop = new FakeDesktop();
  desktop.agents = Array.from({ length: 30 }, (_, index) => ({
    id: `agent-${index.toString(16).padStart(24, "0")}`,
    label: `Task ${index}`,
    state: index === 12 ? "executing" : "idle",
    focused: index === 23,
  }));
  const service = makeService(desktop);
  await service.start();

  const agents = (await service.snapshot()).controller.agents;
  assert.equal(agents.length, 24);
  assert.equal(agents.filter(({ focused }) => focused).length, 1);
});

test("clears a stale agent focus when the desktop no longer reports a selected task", async () => {
  const desktop = new FakeDesktop();
  const service = makeService(desktop);
  await service.start();
  desktop.agents = desktop.agents.map((agent) => ({ ...agent, focused: false }));

  await service.command(bindingCommand("key_accept"), "clear-stale-focus");
  await new Promise((resolve) => setTimeout(resolve, 200));

  const snapshot = await service.snapshot();
  assert.equal(snapshot.controller.focusedAgentId, null);
  assert.equal(snapshot.controller.focusedAgentIndex, -1);
  assert.ok(snapshot.controller.agents.every((agent) => agent.focused === false));
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

test("routes default-layer keys, gestures, joystick, touch, and dial inputs", async () => {
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
    await service.command(bindingCommand(inputId), `binding-${index}`);
  }
  await service.command(bindingCommand("key_clear", { gesture: "hold" }), "clear-hold");
  await service.command({ kind: "model_picker" }, "model-picker");
  await service.command({ kind: "select_model", modelId: "gpt-test" }, "select-model");

  assert.deepEqual(desktop.calls.slice(0, 9), [
    ["press", "approve"],
    ["press", "reject"],
    ["setVoice", true],
    ["press", "new-task"],
    ["press", "stop"],
    ["cycleMode"],
    ["deleteBackward"],
    ["navigate", "up"],
    ["focusAgent", "agent-222222222222222222222222"],
  ]);
  assert.equal(desktop.calls[9][0], "workflow");
  assert.match(desktop.calls[9][1], /Review the current change/);
  assert.deepEqual(desktop.calls[10], ["adjustReasoning", 1]);
  assert.deepEqual(desktop.calls[11], ["clearInput"]);
  assert.deepEqual(desktop.calls[12], ["openModel"]);
  assert.deepEqual(desktop.calls[13], ["selectModel", "gpt-test"]);
});

test("keeps workflow controls available while the visible task is running", async () => {
  const desktop = new FakeDesktop();
  desktop.taskState = "executing";
  desktop.controls = { ...desktop.controls, workflow: true, stop: true, "new-task": true };
  const service = makeService(desktop);
  await service.start();

  const snapshot = await service.snapshot();
  assert.equal(snapshot.controller.taskState, "executing");
  assert.equal(snapshot.controls.workflow, true);
  await service.command(bindingCommand("joystick_down"), "workflow-while-running");

  assert.equal(desktop.calls.at(-1)[0], "workflow");
});

test("acknowledges a desktop action before a slow state scan completes", async () => {
  class DelayedStatusDesktop extends FakeDesktop {
    delayStatus = false;
    releaseStatus = Promise.withResolvers();

    async status() {
      if (this.delayStatus) await this.releaseStatus.promise;
      return super.status();
    }

    async press(control) {
      await super.press(control);
      this.delayStatus = true;
    }
  }

  const desktop = new DelayedStatusDesktop();
  const service = makeService(desktop);
  await service.start();

  const result = await Promise.race([
    service.command(bindingCommand("key_accept"), "fast-ack"),
    new Promise((_, reject) => setTimeout(() => reject(new Error("command waited for state scan")), 50)),
  ]);

  assert.equal(result.status, "succeeded");
  assert.equal(result.result.accepted, true);
  assert.deepEqual(desktop.calls, [["press", "approve"]]);
  desktop.releaseStatus.resolve();
  await service.dispose();
});

test("waits for initial desktop discovery before accepting a control command", async () => {
  class StartingDesktop extends FakeDesktop {
    discovery = Promise.withResolvers();

    async status() {
      await this.discovery.promise;
      return super.status();
    }
  }

  const desktop = new StartingDesktop();
  const service = makeService(desktop);
  const starting = service.start();
  let completed = false;
  const command = service.command(bindingCommand("key_accept"), "startup-command")
    .then((result) => {
      completed = true;
      return result;
    });

  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(completed, false);
  assert.deepEqual(desktop.calls, []);

  desktop.discovery.resolve();
  await starting;
  assert.equal((await command).result.accepted, true);
  assert.deepEqual(desktop.calls, [["press", "approve"]]);
  await service.dispose();
});

test("does not publish or arm polling when stopped during initial discovery", async () => {
  class StartingDesktop extends FakeDesktop {
    discovery = Promise.withResolvers();
    statusCalls = 0;

    async status() {
      this.statusCalls += 1;
      await this.discovery.promise;
      return super.status();
    }
  }

  const desktop = new StartingDesktop();
  const events = new FakeEvents();
  const service = makeService(desktop, events, { pollIntervalMs: 5 });
  const starting = service.start();
  const stopping = service.stop();
  desktop.discovery.resolve();

  await Promise.all([starting, stopping]);
  await new Promise((resolve) => setTimeout(resolve, 15));
  assert.equal(desktop.statusCalls, 1);
  assert.deepEqual(events.published, []);
  await service.dispose();
});

test("publishes model and reasoning only after a fresh desktop scan", async () => {
  class NativeSettingsDesktop extends FakeDesktop {
    statusCalls = 0;

    constructor() {
      super();
      this.model = {
        available: true,
        id: "gpt-old",
        label: "Old",
        options: [
          { id: "gpt-old", label: "Old", selected: true },
          { id: "gpt-new", label: "New", selected: false },
        ],
      };
      this.reasoning = {
        available: true,
        label: "Minimal",
        level: "minimal",
        canIncrease: true,
        canDecrease: false,
      };
    }

    async status() {
      this.statusCalls += 1;
      return super.status();
    }

    async selectModel(modelId, effects) {
      await effects.commit(() => {
        this.calls.push(["selectModel", modelId]);
        this.model = {
          ...this.model,
          id: modelId,
          label: "New",
          options: this.model.options.map((option) => ({ ...option, selected: option.id === modelId })),
        };
      });
      return { message: "Selected New.", settings: { model: this.model, reasoning: this.reasoning } };
    }

    async adjustReasoning(delta, effects) {
      await effects.commit(() => {
        this.calls.push(["adjustReasoning", delta]);
        this.reasoning = {
          available: true,
          label: "Low",
          level: "low",
          canIncrease: true,
          canDecrease: true,
        };
      });
      return { message: "Selected Low reasoning.", settings: { model: this.model, reasoning: this.reasoning } };
    }
  }

  const desktop = new NativeSettingsDesktop();
  const events = new FakeEvents();
  const service = makeService(desktop, events);
  await service.start();
  const initialEvents = events.published.length;

  await service.command({ kind: "select_model", modelId: "gpt-new" }, "native-model");
  assert.equal((await service.snapshot()).controller.model.id, "gpt-old");
  await new Promise((resolve) => setTimeout(resolve, 220));
  assert.equal((await service.snapshot()).controller.model.id, "gpt-new");
  await service.command(bindingCommand("dial_cw"), "native-reasoning");
  assert.equal((await service.snapshot()).controller.reasoning.level, "minimal");
  await new Promise((resolve) => setTimeout(resolve, 220));
  assert.equal((await service.snapshot()).controller.reasoning.level, "low");

  assert.equal(desktop.statusCalls, 3);
  assert.deepEqual(desktop.calls, [
    ["selectModel", "gpt-new"],
    ["adjustReasoning", 1],
  ]);
  assert.equal(events.published.length, initialEvents + 2);
});

test("ignores returned mode settings until a fresh desktop scan", async () => {
  class NativeModeDesktop extends FakeDesktop {
    statusCalls = 0;
    modeLabel = "Codex";

    async status() {
      this.statusCalls += 1;
      const status = await super.status();
      return { ...status, mode: { available: true, label: this.modeLabel } };
    }

    async cycleMode() {
      this.calls.push(["cycleMode"]);
      this.modeLabel = "Plan";
      return {
        message: "Selected the next Codex collaboration mode.",
        settings: { mode: { available: true, label: "Plan" } },
      };
    }
  }

  const desktop = new NativeModeDesktop();
  const events = new FakeEvents();
  const service = makeService(desktop, events);
  await service.start();
  const initialEvents = events.published.length;

  await service.command(bindingCommand("key_mode"), "native-mode");

  assert.deepEqual((await service.snapshot()).controller.mode, { available: true, label: "Codex" });
  await new Promise((resolve) => setTimeout(resolve, 220));
  assert.deepEqual((await service.snapshot()).controller.mode, { available: true, label: "Plan" });
  assert.equal(desktop.statusCalls, 2);
  assert.deepEqual(desktop.calls, [["cycleMode"]]);
  assert.equal(events.published.length, initialEvents + 1);
});

test("defers ordinary action snapshots until the controller surface changes", async () => {
  const events = new FakeEvents();
  const service = makeService(new FakeDesktop(), events);
  await service.start();
  const publishedBeforeAction = events.published.length;

  await service.command(bindingCommand("key_accept"), "deferred-action");
  await new Promise((resolve) => setTimeout(resolve, 240));

  assert.equal(events.published.length, publishedBeforeAction);
  await service.dispose();
});

test("reports a desktop action failure before a slow state scan completes", async () => {
  class DelayedFailureDesktop extends FakeDesktop {
    delayStatus = false;
    releaseStatus = Promise.withResolvers();

    async status() {
      if (this.delayStatus) await this.releaseStatus.promise;
      return super.status();
    }

    async press() {
      this.delayStatus = true;
      throw new Error("The visible Codex control is not available.");
    }
  }

  const desktop = new DelayedFailureDesktop();
  const service = makeService(desktop);
  await service.start();

  await assert.rejects(
    Promise.race([
      service.command(bindingCommand("key_accept"), "fast-error"),
      new Promise((_, reject) => setTimeout(() => reject(new Error("command waited for state scan")), 50)),
    ]),
    (error) => error.code === "command_outcome_indeterminate",
  );

  const operation = await service.commandResult("fast-error");
  assert.equal(operation.status, "unknown");
  assert.equal(operation.error.code, "command_outcome_indeterminate");

  desktop.releaseStatus.resolve();
  await service.dispose();
});

test("records a stale-model preflight rejection as failed and accepts the next command", async () => {
  class StaleModelDesktop extends FakeDesktop {
    async selectModel(modelId) {
      this.calls.push(["selectModelPreflight", modelId]);
      throw new Error("The requested Codex model is unavailable or stale.");
    }
  }

  const desktop = new StaleModelDesktop();
  const service = makeService(desktop);
  await service.start();

  await assert.rejects(
    () => service.command({ kind: "select_model", modelId: "gpt-stale" }, "stale-model-preflight"),
    (error) => error.code === "desktop_action_failed" && /stale/i.test(error.message),
  );
  const failed = await service.commandResult("stale-model-preflight");
  assert.equal(failed.status, "failed");
  assert.equal(failed.error.code, "desktop_action_failed");

  const next = await service.command({ kind: "approve" }, "after-stale-model");
  assert.equal(next.status, "succeeded");
  assert.deepEqual(desktop.calls, [
    ["selectModelPreflight", "gpt-stale"],
    ["press", "approve"],
  ]);
  await service.dispose();
});

test("records a running-task settings preflight as a definite failure", async () => {
  class RunningSettingsDesktop extends FakeDesktop {
    async adjustReasoning(delta) {
      this.calls.push(["reasoningPreflight", delta]);
      throw new Error("Codex reasoning cannot be changed while the visible task is running.");
    }
  }

  const desktop = new RunningSettingsDesktop();
  const service = makeService(desktop);
  await service.start();

  await assert.rejects(
    () => service.command(bindingCommand("dial_cw"), "running-settings-preflight"),
    (error) => error.code === "desktop_action_failed" && /running/i.test(error.message),
  );
  assert.equal((await service.commandResult("running-settings-preflight")).status, "failed");
  assert.deepEqual(desktop.calls, [["reasoningPreflight", 1]]);
  await service.dispose();
});

test("records an app-server settings timeout after dispatch as unknown", async () => {
  class TimeoutSettingsDesktop extends FakeDesktop {
    async selectModel(modelId, effects) {
      this.calls.push(["selectModel", modelId]);
      return effects.commit(() => {
        throw new Error("Codex app-server timed out during thread/settings/update.");
      });
    }
  }

  const desktop = new TimeoutSettingsDesktop();
  const service = makeService(desktop);
  await service.start();

  await assert.rejects(
    () => service.command({ kind: "select_model", modelId: "gpt-test" }, "settings-update-timeout"),
    (error) => error.code === "command_outcome_indeterminate",
  );
  const operation = await service.commandResult("settings-update-timeout");
  assert.equal(operation.status, "unknown");
  assert.equal(operation.error.code, "command_outcome_indeterminate");
  assert.deepEqual(desktop.calls, [["selectModel", "gpt-test"]]);
  await service.dispose();
});

test("records a post-update settings observation mismatch as unknown", async () => {
  class MismatchSettingsDesktop extends FakeDesktop {
    async adjustReasoning(delta, effects) {
      this.calls.push(["adjustReasoning", delta]);
      await effects.commit(async () => {});
      throw new Error("Codex did not confirm the requested settings for the bound task.");
    }
  }

  const desktop = new MismatchSettingsDesktop();
  const service = makeService(desktop);
  await service.start();

  await assert.rejects(
    () => service.command(bindingCommand("dial_cw"), "settings-observation-mismatch"),
    (error) => error.code === "command_outcome_indeterminate",
  );
  assert.equal((await service.commandResult("settings-observation-mismatch")).status, "unknown");
  assert.deepEqual(desktop.calls, [["adjustReasoning", 1]]);
  await service.dispose();
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

test("does not publish transient push-to-talk snapshots", async () => {
  const events = new FakeEvents();
  const desktop = new FakeDesktop();
  const service = makeService(desktop, events);
  await service.start();
  const publishedBeforeVoice = events.published.length;

  await service.command({ kind: "voice_start" }, "voice-no-flash-down");
  await service.command({ kind: "voice_stop" }, "voice-no-flash-up");
  await new Promise((resolve) => setTimeout(resolve, 220));

  assert.equal(events.published.length, publishedBeforeVoice);
  assert.equal((await service.snapshot()).controller.voice.active, false);
  await service.dispose();
});

test("keeps confirmed desktop push-to-talk state across a desktop refresh", async () => {
  const desktop = new FakeDesktop();
  const service = makeService(desktop);
  await service.start();

  await service.command({ kind: "voice_start" }, "voice-refresh-start");
  assert.equal((await service.snapshot()).controller.voice.active, true);

  await new Promise((resolve) => setTimeout(resolve, 220));
  assert.equal((await service.snapshot()).controller.voice.active, true);

  await service.command({ kind: "voice_stop" }, "voice-refresh-stop");
  assert.equal((await service.snapshot()).controller.voice.active, false);
  await service.dispose();
});

test("switches layers and rejects inputs that are not mapped on that layer", async () => {
  const service = makeService();
  await service.start();

  await service.command({ kind: "select_layer", layerId: "layer-2" }, "layer-2");
  assert.equal((await service.snapshot()).controller.activeLayerId, "layer-2");
  await assert.rejects(
    () => service.command(bindingCommand("key_voice", { layerId: "layer-2" }), "layer-2-voice"),
    (error) => error.code === "unmapped_input",
  );
});

test("rejects a legacy binding without an observed layer and action", async () => {
  const desktop = new FakeDesktop();
  const service = makeService(desktop);
  await service.start();

  await assert.rejects(
    () => service.command({ kind: "binding", inputId: "key_accept" }, "legacy-binding"),
    (error) => error.code === "invalid_controller_configuration",
  );

  assert.deepEqual(desktop.calls, []);
  await service.dispose();
});

test("rejects a binding captured from a stale layer before desktop dispatch", async () => {
  const desktop = new FakeDesktop();
  const service = makeService(desktop);
  await service.start();
  const stale = bindingCommand("key_accept");

  await service.command({ kind: "select_layer", layerId: "layer-2" }, "move-to-layer-2");
  await assert.rejects(
    () => service.command(stale, "stale-layer-binding"),
    (error) => error.code === "stale_controller_context",
  );

  assert.deepEqual(desktop.calls, []);
  await service.dispose();
});

test("rejects a binding whose action changed on the same layer", async () => {
  const desktop = new FakeDesktop();
  const service = makeService(desktop);
  await service.start();
  const stale = bindingCommand("key_accept");

  await service.command({
    kind: "update_binding",
    layerId: "layer-1",
    inputId: "key_accept",
    gesture: "tap",
    action: { type: "reject" },
  }, "remap-accept");
  await assert.rejects(
    () => service.command(stale, "stale-action-binding"),
    (error) => error.code === "stale_controller_context",
  );

  assert.deepEqual(desktop.calls, []);
  await service.dispose();
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

test("fingerprints validated command bodies independent of property order", async () => {
  const desktop = new FakeDesktop();
  const service = makeService(desktop);
  await service.start();

  const command = bindingCommand("key_accept");
  const first = await service.command(command, "reordered-key");
  const replay = await service.command({
    action: command.action,
    layerId: command.layerId,
    gesture: command.gesture,
    inputId: command.inputId,
    kind: command.kind,
  }, "reordered-key");

  assert.deepEqual(replay, first);
  assert.deepEqual(desktop.calls, [["press", "approve"]]);
  await service.dispose();
});

test("coalesces concurrent duplicate commands and waits for one terminal result", async () => {
  class BlockingDesktop extends FakeDesktop {
    started = Promise.withResolvers();
    release = Promise.withResolvers();

    async press(control) {
      this.calls.push(["press", control]);
      this.started.resolve();
      await this.release.promise;
    }
  }

  const desktop = new BlockingDesktop();
  const service = makeService(desktop);
  await service.start();
  const command = bindingCommand("key_accept");
  const first = service.command(command, "concurrent-duplicate");
  await desktop.started.promise;
  let replaySettled = false;
  const replay = service.command({
    action: command.action,
    layerId: command.layerId,
    gesture: command.gesture,
    inputId: command.inputId,
    kind: command.kind,
  }, "concurrent-duplicate")
    .then((operation) => {
      replaySettled = true;
      return operation;
    });
  await new Promise(setImmediate);
  assert.equal(replaySettled, false);

  desktop.release.resolve();
  const [firstResult, replayResult] = await Promise.all([first, replay]);
  assert.deepEqual(replayResult, firstResult);
  assert.equal(firstResult.status, "succeeded");
  assert.deepEqual(desktop.calls, [["press", "approve"]]);
  await service.dispose();
});

test("does not dispatch when persisting running fails", async (t) => {
  const path = await temporaryOperationPath(t);
  let commits = 0;
  const operations = new Operations({
    path,
    commit: (destination, entries) => {
      commits += 1;
      if (commits === 2) throw new Error("running write failed");
      persistOperations(destination, entries);
    },
  });
  const desktop = new FakeDesktop();
  const service = makeService(desktop, new FakeEvents(), { operations });
  await service.start();

  await assert.rejects(
    () => service.command({ kind: "approve" }, "running-persistence-failure"),
    (error) => error.code === "operation_persistence_failed",
  );
  assert.deepEqual(desktop.calls, []);
  const operation = await service.commandResult("running-persistence-failure");
  assert.equal(operation.status, "failed");
  assert.equal(operation.error.code, "operation_persistence_failed");
  await service.dispose();
});

test("reports and retains unknown when terminal persistence fails after dispatch", async (t) => {
  const path = await temporaryOperationPath(t);
  let commits = 0;
  const operations = new Operations({
    path,
    commit: (destination, entries) => {
      commits += 1;
      if (commits === 3) throw new Error("terminal write failed");
      persistOperations(destination, entries);
    },
  });
  const desktop = new FakeDesktop();
  const service = makeService(desktop, new FakeEvents(), { operations });
  await service.start();

  await assert.rejects(
    () => service.command({ kind: "approve" }, "terminal-persistence-failure"),
    (error) => error.code === "command_outcome_indeterminate",
  );
  const operation = await service.commandResult("terminal-persistence-failure");
  assert.equal(operation.status, "unknown");
  assert.equal(operation.error.code, "command_outcome_indeterminate");

  const replay = await service.command({ kind: "approve" }, "terminal-persistence-failure");
  assert.equal(replay.status, "unknown");
  assert.deepEqual(desktop.calls, [["press", "approve"]]);
  assert.equal(new Operations({ path }).get("terminal-persistence-failure", "principal:local-root").status, "unknown");
  await service.dispose();
});

test("retains an unknown terminal operation when dispatch reports an error", async () => {
  class AmbiguousDesktop extends FakeDesktop {
    async press(control) {
      this.calls.push(["press", control]);
      throw new Error("response channel closed after dispatch");
    }
  }

  const desktop = new AmbiguousDesktop();
  const service = makeService(desktop);
  await service.start();

  await assert.rejects(
    () => service.command({ kind: "stop" }, "ambiguous-action"),
    (error) => error.code === "command_outcome_indeterminate",
  );
  const replay = await service.command({ kind: "stop" }, "ambiguous-action");
  assert.equal(replay.status, "unknown");
  assert.equal(replay.error.code, "command_outcome_indeterminate");
  assert.deepEqual(desktop.calls, [["press", "stop"]]);
  await service.dispose();
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
    (error) => error.code === "command_outcome_indeterminate",
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

test("keeps the last good desktop snapshot across one transient polling failure", async () => {
  class FlakyDesktop extends FakeDesktop {
    failuresRemaining = 0;
    failed = Promise.withResolvers();

    async status() {
      if (this.failuresRemaining > 0) {
        this.failuresRemaining -= 1;
        this.failed.resolve();
        throw new Error("temporary desktop timeout");
      }
      return super.status();
    }
  }

  const desktop = new FlakyDesktop();
  const events = new FakeEvents();
  const service = makeService(desktop, events, { pollIntervalMs: 50 });
  await service.start();
  const publishedAfterStart = events.published.length;
  desktop.failuresRemaining = 1;

  await desktop.failed.promise;
  await new Promise(setImmediate);
  const snapshot = await service.snapshot();

  assert.equal(snapshot.status.state, "ready");
  assert.equal(snapshot.controller.agents.length, 2);
  assert.equal(events.published.length, publishedAfterStart);
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

test("does not start a background scan while a controller command is queued", async () => {
  class CommandBlockingDesktop extends FakeDesktop {
    statusCalls = 0;
    releasePress = Promise.withResolvers();

    async status() {
      this.statusCalls += 1;
      return super.status();
    }

    async press(control) {
      await this.releasePress.promise;
      await super.press(control);
    }
  }

  const desktop = new CommandBlockingDesktop();
  const service = makeService(desktop, new FakeEvents(), { pollIntervalMs: 5 });
  await service.start();
  const statusCallsAfterStart = desktop.statusCalls;
  const command = service.command(bindingCommand("key_accept"), "queued-command");

  await new Promise((resolve) => setTimeout(resolve, 32));
  assert.equal(desktop.statusCalls, statusCallsAfterStart);

  desktop.releasePress.resolve();
  await command;
  await service.dispose();
});

test("discards an old poll that completes after a newer native action", async () => {
  class RacingDesktop extends FakeDesktop {
    statusCalls = 0;
    pollStarted = Promise.withResolvers();
    releasePoll = Promise.withResolvers();

    constructor() {
      super();
      this.model = {
        available: true,
        id: "gpt-old",
        label: "Old",
        options: [
          { id: "gpt-old", label: "Old", selected: true },
          { id: "gpt-new", label: "New", selected: false },
        ],
      };
    }

    async status() {
      this.statusCalls += 1;
      const snapshot = await super.status();
      if (this.statusCalls === 2) {
        this.pollStarted.resolve();
        await this.releasePoll.promise;
      }
      return snapshot;
    }

    async selectModel(modelId, effects) {
      await effects.commit(() => {
        this.calls.push(["selectModel", modelId]);
        this.model = {
          ...this.model,
          id: modelId,
          label: "New",
          options: this.model.options.map((option) => ({ ...option, selected: option.id === modelId })),
        };
      });
      return { message: "Selected New.", settings: { model: this.model, reasoning: this.reasoning } };
    }
  }

  const desktop = new RacingDesktop();
  const service = makeService(desktop, new FakeEvents(), { pollIntervalMs: 5 });
  await service.start();
  await desktop.pollStarted.promise;

  await service.command({ kind: "select_model", modelId: "gpt-new" }, "newer-action");
  desktop.releasePoll.resolve();
  await new Promise((resolve) => setTimeout(resolve, 220));

  assert.equal((await service.snapshot()).controller.model.id, "gpt-new");
  await service.dispose();
});

test("revalidates a queued command principal after revocation", async () => {
  class BlockingDesktop extends FakeDesktop {
    firstStarted = Promise.withResolvers();
    releaseFirst = Promise.withResolvers();

    async press(control) {
      this.calls.push(["press", control]);
      if (this.calls.length === 1) {
        this.firstStarted.resolve();
        await this.releaseFirst.promise;
      }
    }
  }

  const desktop = new BlockingDesktop();
  const service = makeService(desktop);
  await service.start();
  const first = service.command({ kind: "approve" }, "principal-blocker");
  await desktop.firstStarted.promise;
  let active = true;
  const principal = { id: "device:test", revocable: true, valid: () => active };
  const queued = service.command({ kind: "reject" }, "principal-queued", principal);

  active = false;
  desktop.releaseFirst.resolve();
  await first;
  await assert.rejects(queued, (error) => error.code === "credential_revoked");
  const rejectedOperation = await service.commandResult("principal-queued", principal);
  assert.equal(rejectedOperation.status, "failed");
  assert.equal(rejectedOperation.error.code, "credential_revoked");
  assert.deepEqual(desktop.calls, [["press", "approve"]]);
  await service.dispose();
});

test("defers revoked-principal cleanup until an in-flight command has a terminal record", async () => {
  class BlockingDesktop extends FakeDesktop {
    started = Promise.withResolvers();
    release = Promise.withResolvers();

    async press(control) {
      this.calls.push(["press", control]);
      this.started.resolve();
      await this.release.promise;
    }
  }

  const desktop = new BlockingDesktop();
  const service = makeService(desktop);
  await service.start();
  let active = true;
  const principal = { id: "device:cleanup", revocable: true, valid: () => active };
  const command = service.command({ kind: "approve" }, "cleanup-in-flight", principal);
  await desktop.started.promise;

  active = false;
  assert.equal(service.revokePrincipal(principal.id), false);
  assert.equal((await service.commandResult("cleanup-in-flight", principal)).status, "running");

  desktop.release.resolve();
  assert.equal((await command).status, "succeeded");
  await new Promise(setImmediate);
  await assert.rejects(
    () => service.commandResult("cleanup-in-flight", principal),
    (error) => error.status === 404 && error.code === "operation_not_found",
  );
  await service.dispose();
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
  let snapshot = await service.snapshot();
  await service.command(bindingCommand("key_voice", {
    gesture: "double_tap",
    layerId: "layer-2",
    profile: snapshot.controller.profile,
  }), "dispatch-double-tap");
  assert.deepEqual(desktop.calls, [["navigate", "left"]]);

  await assert.rejects(
    () => service.command(bindingCommand("key_voice", {
      layerId: "layer-2",
      profile: snapshot.controller.profile,
    }), "unmapped-legacy-tap"),
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

  await restarted.command(bindingCommand("key_focus", {
    profile: snapshot.controller.profile,
  }), "switch-with-binding");
  snapshot = await restarted.snapshot();
  assert.equal(snapshot.controller.activeLayerId, "layer-2");
  await restarted.command({ kind: "select_layer", layerId: "layer-1" }, "return-layer-1");
  snapshot = await restarted.snapshot();
  await restarted.command(bindingCommand("joystick_down", {
    profile: snapshot.controller.profile,
  }), "run-custom-debug");
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
