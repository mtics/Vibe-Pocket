import { randomUUID } from "node:crypto";

import {
  ACTIONS,
  GESTURES,
  ValidationError,
  createDefault,
  normalize,
  validateAction,
  workflowPrompt,
} from "../profile/model.mjs";
import { Failure } from "../server/failure.mjs";
import { resolve } from "./command.mjs";
import { Queue } from "./queue.mjs";
import { Refresh } from "./refresh.mjs";
import { State } from "./state.mjs";

const TASK_ID = "vibe-pocket-codex";
const DEFAULT_POLL_INTERVAL_MS = 2_000;
const HOOK_EVENTS = new Set(["UserPromptSubmit", "PreToolUse", "PermissionRequest", "PostToolUse", "Stop"]);

export class Session {
  #desktop;
  #profile;
  #profileStore;
  #activeLayerId;
  #state;
  #queue = new Queue();
  #refresh;

  constructor({
    workspaces,
    events,
    desktop,
    profile,
    profileStore = null,
    pollIntervalMs = DEFAULT_POLL_INTERVAL_MS,
  }) {
    if (!desktop) {
      throw new TypeError("The control session requires a visible Codex desktop.");
    }
    this.#desktop = desktop;
    this.#profile = profile ? normalize(profile) : createDefault();
    this.#profileStore = profileStore;
    this.#activeLayerId = this.#profile.layers[0].id;
    this.#state = new State({ events, workspaces, taskId: TASK_ID });
    this.#refresh = new Refresh({
      desktop,
      state: this.#state,
      blocked: () => this.#queue.busy,
      intervalMs: pollIntervalMs,
    });
  }

  async start() {
    if (this.#profileStore) {
      this.#profile = await this.#profileStore.load();
      this.#activeLayerId = this.#profile.layers[0].id;
    }
    await this.#refresh.start();
    this.#state.publish("snapshot_changed");
  }

  async dispose() {
    await this.#refresh.stop();
    await this.#desktop.dispose?.();
  }

  async snapshot() {
    return this.#state.snapshot({
      profile: this.#profile,
      gestures: GESTURES,
      actions: ACTIONS,
      activeLayerId: this.#activeLayerId,
      taskId: TASK_ID,
    });
  }

  async bindDesktopThread(threadId) {
    return this.#queue.run(async () => {
      await this.#perform(
        () => this.#desktop.bindThread(threadId),
        "Attached the current Codex desktop task.",
      );
      return { attached: true, revision: this.#state.revision };
    });
  }

  async codexHook(event, payload) {
    if (!HOOK_EVENTS.has(event)) {
      throw new Failure(400, "invalid_hook_event", "Unsupported Codex lifecycle event.");
    }
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      throw new Failure(400, "invalid_hook_payload", "Codex lifecycle payload must be a JSON object.");
    }

    const lifecycle = await this.#desktop.applyLifecycleHook(event, payload);
    await this.#refresh.now({ publishIfChanged: true });
    const response = await lifecycle.response;
    await this.#refresh.now({ publishIfChanged: true });
    return response;
  }

  async command(command, idempotencyKey) {
    return this.#queue.once(idempotencyKey, command, async () => {
      await this.#execute(command);
      return { commandId: `cmd_${randomUUID()}`, accepted: true, revision: this.#state.revision };
    });
  }

  async #execute(command) {
    try {
      const intent = resolve(command, { profile: this.#profile, layerId: this.#activeLayerId });
      if (intent.kind === "voice") {
        await this.#setVoice(intent.active);
        return;
      }
      if (intent.kind === "agent") {
        await this.#focusAgent(intent.id);
        return;
      }
      if (intent.kind === "action") {
        await this.#executeAction(intent.value);
        return;
      }
      if (intent.kind === "layer") {
        const layer = this.#profile.layers.find(({ id }) => id === intent.id);
        this.#activeLayerId = layer.id;
        this.#state.record(`Selected ${layer.name}.`);
        return;
      }
      if (intent.kind === "profile") {
        await this.#replaceProfile(intent.value, intent.message, { resetLayer: intent.resetLayer });
      }
    } catch (error) {
      if (error instanceof Failure) throw error;
      if (error instanceof ValidationError) {
        throw new Failure(400, "invalid_controller_configuration", error.message);
      }
      const message = error.message || "Codex did not accept this controller action.";
      this.#state.reject(message);
      this.#refresh.afterAction();
      throw new Failure(409, "desktop_action_failed", message);
    }
  }

  async #executeAction(action) {
    action = validateAction(action);
    switch (action.type) {
      case "attach":
        await this.#perform(() => this.#desktop.attach(), "Resumed the focused Vibe Pocket Codex task.");
        return;
      case "voice":
        await this.#setVoice(!this.#state.voice.active);
        return;
      case "stop":
        await this.#press("stop", "Stopped the focused Codex turn.");
        return;
      case "approve":
        await this.#press("approve", "Approved the focused Codex request or submitted its draft.");
        return;
      case "reject":
        await this.#press("reject", "Rejected the focused Codex request or discarded its draft.");
        return;
      case "new_task":
        await this.#press("new-task", "Created a new Vibe Pocket Codex task.");
        return;
      case "navigate":
        if (!["up", "down", "left", "right"].includes(action.direction)) {
          throw new Failure(400, "invalid_direction", "Navigation direction must be up, down, left, or right.");
        }
        await this.#perform(() => this.#desktop.navigate(action.direction), `Moved ${action.direction} in Codex.`);
        return;
      case "mode_cycle":
        await this.#perform(() => this.#desktop.cycleMode(), "Selected the next Codex mode.");
        return;
      case "access_cycle":
        await this.#perform(() => this.#desktop.cycleAccess(), "Selected the next Codex access level.");
        return;
      case "clear_input":
        await this.#perform(() => this.#desktop.clearInput(), "Cleared the visible Codex input.");
        return;
      case "focus_next": {
        const agents = this.#state.agents;
        if (agents.length === 0) {
          await this.#perform(() => this.#desktop.attach(), "Resumed the focused Vibe Pocket Codex task.");
          return;
        }
        const nextIndex = (this.#state.focusedAgentIndex + 1) % agents.length;
        await this.#perform(() => this.#desktop.focusAgent(agents[nextIndex].id), `Focused ${agents[nextIndex].label}.`);
        this.#state.focus(agents[nextIndex].id);
        return;
      }
      case "focus_agent": {
        const agent = this.#state.agents[action.index];
        if (!agent) {
          throw new Failure(409, "agent_slot_unavailable", "That Codex agent slot is not currently available.");
        }
        await this.#perform(() => this.#desktop.focusAgent(agent.id), `Focused ${agent.label}.`);
        this.#state.focus(agent.id);
        return;
      }
      case "select_layer": {
        const layer = this.#profile.layers.find(({ id }) => id === action.layerId);
        if (!layer) throw new Failure(409, "layer_unavailable", "That controller layer is unavailable.");
        this.#activeLayerId = layer.id;
        this.#state.record(`Selected ${layer.name}.`);
        return;
      }
      case "reasoning_depth":
        if (action.delta !== 1 && action.delta !== -1) {
          throw new Failure(400, "invalid_reasoning_delta", "Reasoning adjustment must be one step clockwise or counter-clockwise.");
        }
        await this.#perform(() => this.#desktop.adjustReasoning(action.delta), "Adjusted Codex reasoning depth.");
        return;
      case "workflow":
        await this.#perform(
          () => this.#desktop.workflow(workflowPrompt(this.#profile, action.workflowId)),
          "Started the selected workflow in a new Codex task.",
        );
        return;
      default:
        throw new Failure(400, "unsupported_command", "This Vibe Pocket controller action is not supported.");
    }
  }

  async #replaceProfile(nextProfile, message, { resetLayer = false } = {}) {
    let persisted = normalize(nextProfile);
    if (this.#profileStore) {
      try {
        persisted = await this.#profileStore.save(persisted);
      } catch {
        throw new Failure(500, "profile_persistence_failed", "The controller profile could not be saved.");
      }
    }
    this.#profile = persisted;
    if (resetLayer || !this.#profile.layers.some(({ id }) => id === this.#activeLayerId)) {
      this.#activeLayerId = this.#profile.layers[0].id;
    }
    this.#state.record(message);
  }

  async #perform(operation, fallbackMessage) {
    const result = await operation();
    // The scheduled capability scan verifies desktop actions. Avoid publishing
    // a transient success message before that scan changes the controller UI.
    this.#state.record(result?.message ?? fallbackMessage, { publish: false });
    this.#refresh.afterAction();
  }

  async #press(control, fallbackMessage) {
    await this.#perform(() => this.#desktop.press(control), fallbackMessage);
  }

  async #setVoice(active) {
    if (active && !this.#state.voice.available) {
      throw new Failure(409, "voice_unavailable", "The visible ChatGPT Codex dictation control is unavailable.");
    }
    const result = await this.#desktop.setVoice(active);
    this.#state.setVoice(active);
    // A press-to-talk release can enqueue the matching stop immediately after
    // start. Let the common delayed scan publish the stable final state once.
    this.#state.record(
      result?.message ?? (active ? "Started ChatGPT Codex dictation." : "Stopped ChatGPT Codex dictation."),
      { publish: false },
    );
    this.#refresh.afterAction();
  }

  async #focusAgent(agentId) {
    const index = this.#state.agents.findIndex((agent) => agent.id === agentId);
    if (index < 0) {
      throw new Failure(409, "agent_unavailable", "That Codex agent is no longer available.");
    }
    const agent = this.#state.agents[index];
    await this.#perform(() => this.#desktop.focusAgent(agent.id), `Focused ${agent.label}.`);
    this.#state.focus(agent.id);
  }
}
