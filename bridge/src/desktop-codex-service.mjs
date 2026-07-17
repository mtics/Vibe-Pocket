import { randomUUID } from "node:crypto";
import { EventEmitter } from "node:events";

import {
  CONTROLLER_ACTION_CATALOG,
  CONTROLLER_GESTURES,
  ControllerProfileValidationError,
  bindingFor,
  clearControllerBinding,
  createDefaultControllerProfile,
  normalizeControllerProfile,
  renameControllerLayer,
  updateControllerBinding,
  validateControllerAction,
  validateGesture,
  validateInputId,
  validateLayerId,
  workflowPrompt,
} from "./controller-profile.mjs";
import { MacCodexDesktopController } from "./macos-codex-desktop.mjs";
import { PocketError } from "./pocket-controller-service.mjs";

const DESKTOP_SESSION_ID = "desktop-codex";
const MAX_IDEMPOTENCY_ENTRIES = 256;
const TASK_STATES = new Set(["idle", "unread", "thinking", "executing", "waiting", "complete", "error"]);

export class DesktopCodexService extends EventEmitter {
  #workspaces;
  #events;
  #desktop;
  #profile;
  #profileStore;
  #activeLayerId;
  #idempotency = new Map();
  #revision = 0;
  #status = { state: "starting", message: null, controls: emptyControls() };
  #controllerState = emptyControllerState();
  #session = null;
  #commandQueue = Promise.resolve();
  #pollIntervalMs;
  #pollTimer = null;
  #pollRefresh = null;

  constructor({
    workspaces,
    events,
    desktop = new MacCodexDesktopController(),
    profile,
    profileStore = null,
    pollIntervalMs = 1_000,
  }) {
    super();
    this.#workspaces = workspaces;
    this.#events = events;
    this.#desktop = desktop;
    this.#profile = profile ? normalizeControllerProfile(profile) : createDefaultControllerProfile();
    this.#profileStore = profileStore;
    this.#activeLayerId = this.#profile.layers[0].id;
    this.#pollIntervalMs = pollIntervalMs;
    this.#session = {
      id: DESKTOP_SESSION_ID,
      workspaceId: "current desktop task",
      state: "starting",
      terminalTail: "Checking the visible ChatGPT Codex task...",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      canInterrupt: false,
    };
  }

  async start() {
    if (this.#profileStore) {
      this.#profile = await this.#profileStore.load();
      this.#activeLayerId = this.#profile.layers[0].id;
    }
    await this.#refreshAvailability();
    this.#touch("snapshot_changed");
    if (this.#pollIntervalMs > 0) {
      this.#pollTimer = setInterval(() => {
        this.#schedulePollRefresh();
      }, this.#pollIntervalMs);
      this.#pollTimer.unref?.();
    }
  }

  async dispose() {
    if (this.#pollTimer) clearInterval(this.#pollTimer);
    this.#pollTimer = null;
    await this.#pollRefresh?.catch(() => {});
    this.#pollRefresh = null;
  }

  async snapshot() {
    return {
      revision: `r_${this.#revision}`,
      status: this.#status,
      focusSessionId: this.#session.state === "error" ? null : DESKTOP_SESSION_ID,
      workspaces: Object.keys(this.#workspaces),
      controls: this.#status.controls,
      controller: {
        profile: this.#profile,
        gestures: CONTROLLER_GESTURES,
        actionCatalog: CONTROLLER_ACTION_CATALOG,
        activeLayerId: this.#activeLayerId,
        ...this.#controllerState,
      },
      sessions: [this.#publicSession()],
    };
  }

  async command(command, idempotencyKey) {
    if (!idempotencyKey || idempotencyKey.length > 160) {
      throw new PocketError(400, "idempotency_key_required", "Every controller action needs an Idempotency-Key header.");
    }

    const fingerprint = JSON.stringify(command);
    const existing = this.#idempotency.get(idempotencyKey);
    if (existing) {
      if (existing.fingerprint !== fingerprint) {
        throw new PocketError(409, "idempotency_key_reused", "This Idempotency-Key was already used for another action.");
      }
      return existing.execution;
    }

    const execution = this.#commandQueue
      .then(() => this.#execute(command))
      .then(() => ({ commandId: `cmd_${randomUUID()}`, accepted: true, revision: `r_${this.#revision}` }))
      .catch((error) => {
        this.#idempotency.delete(idempotencyKey);
        throw error;
      });
    this.#commandQueue = execution.catch(() => {});
    this.#idempotency.set(idempotencyKey, { fingerprint, execution });
    this.#trimIdempotencyCache();
    return execution;
  }

  async #execute(command) {
    if (!command || typeof command !== "object" || Array.isArray(command)) {
      throw new PocketError(400, "invalid_command", "Controller action must be a JSON object.");
    }

    try {
      if (command.kind === "voice_start" || command.kind === "voice_stop") {
        requireCommandKeys(command, ["kind"]);
        await this.#setVoice(command.kind === "voice_start");
        return;
      }

      if (command.kind === "focus_agent" && Object.hasOwn(command, "agentId")) {
        requireCommandKeys(command, ["kind", "agentId"]);
        if (typeof command.agentId !== "string" || !/^agent-[a-f0-9]{24}$/.test(command.agentId)) {
          throw new ControllerProfileValidationError("A stable Codex agent ID is required.");
        }
        await this.#focusAgentById(command.agentId);
        return;
      }

      if (command.kind === "binding") {
        requireCommandKeys(command, ["kind", "inputId"], ["gesture"]);
        validateInputId(command.inputId);
        const gesture = validateGesture(command.gesture ?? "tap");
        const action = bindingFor(this.#profile, this.#activeLayerId, command.inputId, gesture);
        if (!action) {
          throw new PocketError(409, "unmapped_input", "This controller gesture has no action on the active layer.");
        }
        await this.#executeAction(action);
        return;
      }

      if (command.kind === "select_layer") {
        requireCommandKeys(command, ["kind", "layerId"]);
        validateLayerId(command.layerId);
        const layer = this.#profile.layers.find((candidate) => candidate.id === command.layerId);
        this.#activeLayerId = layer.id;
        this.#recordAction(`Selected ${layer.name}.`);
        return;
      }

      if (command.kind === "update_binding") {
        requireCommandKeys(command, ["kind", "layerId", "inputId", "action"], ["gesture"]);
        const next = updateControllerBinding(this.#profile, {
          layerId: command.layerId,
          inputId: command.inputId,
          gesture: command.gesture ?? "tap",
          action: command.action,
        });
        await this.#replaceProfile(next, "Updated controller binding.");
        return;
      }

      if (command.kind === "clear_binding") {
        requireCommandKeys(command, ["kind", "layerId", "inputId"], ["gesture"]);
        const next = clearControllerBinding(this.#profile, {
          layerId: command.layerId,
          inputId: command.inputId,
          gesture: command.gesture ?? "tap",
        });
        await this.#replaceProfile(next, "Cleared controller binding.");
        return;
      }

      if (command.kind === "rename_layer") {
        requireCommandKeys(command, ["kind", "layerId", "name"]);
        const next = renameControllerLayer(this.#profile, {
          layerId: command.layerId,
          name: command.name,
        });
        await this.#replaceProfile(next, `Renamed ${command.layerId}.`);
        return;
      }

      if (command.kind === "reset_profile") {
        requireCommandKeys(command, ["kind"]);
        await this.#replaceProfile(createDefaultControllerProfile(), "Reset all controller layers.", { resetLayer: true });
        return;
      }

      await this.#executeAction(legacyAction(command));
    } catch (error) {
      if (error instanceof PocketError) throw error;
      if (error instanceof ControllerProfileValidationError) {
        throw new PocketError(400, "invalid_controller_configuration", error.message);
      }
      const message = error.message || "The visible Codex task did not accept this action.";
      await this.#refreshAvailability();
      this.#status = { ...this.#status, message };
      this.#session.terminalTail = message;
      this.#session.updatedAt = new Date().toISOString();
      this.#touch("snapshot_changed");
      throw new PocketError(409, "desktop_action_failed", message);
    }
  }

  async #executeAction(action) {
    action = validateControllerAction(action);
    switch (action.type) {
      case "attach":
        await this.#perform(() => this.#desktop.attach(), "Attached to the visible ChatGPT Codex task.");
        return;
      case "voice":
        await this.#setVoice(!this.#controllerState.voice.active);
        return;
      case "stop":
        await this.#press("stop", "Pressed Stop in the visible ChatGPT Codex task.");
        return;
      case "approve":
        await this.#press("approve", "Approved the visible ChatGPT Codex request.");
        return;
      case "reject":
        await this.#press("reject", "Rejected the visible ChatGPT Codex request.");
        return;
      case "new_task":
        await this.#press("new-task", "Created a new visible ChatGPT Codex task.");
        return;
      case "navigate":
        if (!["up", "down", "left", "right"].includes(action.direction)) {
          throw new PocketError(400, "invalid_direction", "Navigation direction must be up, down, left, or right.");
        }
        await this.#perform(() => this.#desktop.navigate(action.direction), `Moved ${action.direction} in Codex.`);
        return;
      case "mode_cycle":
        await this.#perform(() => this.#desktop.cycleMode(), "Selected the next Codex mode.");
        return;
      case "clear_input":
        await this.#perform(() => this.#desktop.clearInput(), "Cleared the visible Codex draft.");
        return;
      case "focus_next": {
        const agents = this.#controllerState.agents;
        if (agents.length === 0) {
          await this.#perform(() => this.#desktop.attach(), "Focused the visible ChatGPT Codex task.");
          return;
        }
        const nextIndex = (this.#controllerState.focusedAgentIndex + 1) % agents.length;
        await this.#perform(() => this.#desktop.focusAgent(agents[nextIndex].id), `Focused ${agents[nextIndex].label}.`);
        this.#markFocusedAgent(agents[nextIndex].id);
        return;
      }
      case "focus_agent": {
        const agent = this.#controllerState.agents[action.index];
        if (!agent) {
          throw new PocketError(409, "agent_slot_unavailable", "That Codex agent slot is not currently available.");
        }
        await this.#perform(() => this.#desktop.focusAgent(agent.id), `Focused ${agent.label}.`);
        this.#markFocusedAgent(agent.id);
        return;
      }
      case "reasoning_depth":
        if (action.delta !== 1 && action.delta !== -1) {
          throw new PocketError(400, "invalid_reasoning_delta", "Reasoning adjustment must be one step clockwise or counter-clockwise.");
        }
        await this.#perform(() => this.#desktop.adjustReasoning(action.delta), "Adjusted Codex reasoning depth.");
        return;
      case "workflow":
        await this.#perform(
          () => this.#desktop.workflow(workflowPrompt(action.workflowId)),
          "Started the selected workflow in a new Codex task.",
        );
        return;
      default:
        throw new PocketError(400, "unsupported_command", "This Vibe Pocket controller action is not supported.");
    }
  }

  async #replaceProfile(nextProfile, message, { resetLayer = false } = {}) {
    let persisted = normalizeControllerProfile(nextProfile);
    if (this.#profileStore) {
      try {
        persisted = await this.#profileStore.save(persisted);
      } catch {
        throw new PocketError(500, "profile_persistence_failed", "The controller profile could not be saved.");
      }
    }
    this.#profile = persisted;
    if (resetLayer || !this.#profile.layers.some(({ id }) => id === this.#activeLayerId)) {
      this.#activeLayerId = this.#profile.layers[0].id;
    }
    this.#recordAction(message);
  }

  #schedulePollRefresh() {
    if (this.#pollRefresh) return;
    this.#pollRefresh = this.#refreshAvailability({ publishIfChanged: true })
      .finally(() => { this.#pollRefresh = null; });
  }

  async #refreshAvailability({ publishIfChanged = false } = {}) {
    const before = this.#stateFingerprint();
    try {
      const result = await this.#desktop.status();
      this.#status = {
        state: "ready",
        message: result.message ?? null,
        controls: normalizeControls(result.controls),
      };
      this.#controllerState = normalizeControllerState(result, this.#controllerState.focusedAgentId);
      this.#session.state = this.#controllerState.taskState === "error" ? "error" : "active";
      this.#session.canInterrupt = this.#status.controls.stop;
      this.#session.terminalTail = result.message ?? "Ready to control the visible ChatGPT Codex task.";
    } catch (error) {
      this.#status = {
        state: "degraded",
        message: error.message || "Desktop Codex is unavailable.",
        controls: emptyControls(),
      };
      this.#controllerState = { ...emptyControllerState(), taskState: "error" };
      this.#session.state = "error";
      this.#session.canInterrupt = false;
      this.#session.terminalTail = this.#status.message;
    }
    this.#session.updatedAt = new Date().toISOString();
    if (publishIfChanged && before !== this.#stateFingerprint()) this.#touch("snapshot_changed");
  }

  async #perform(operation, fallbackMessage) {
    const result = await operation();
    await this.#refreshAvailability();
    this.#recordAction(result?.message ?? fallbackMessage);
  }

  async #press(control, fallbackMessage) {
    await this.#perform(() => this.#desktop.press(control), fallbackMessage);
  }

  async #setVoice(active) {
    await this.#perform(
      () => this.#desktop.setVoice(active),
      active ? "Started Codex dictation on the M5." : "Stopped Codex dictation on the M5.",
    );
  }

  async #focusAgentById(agentId) {
    const index = this.#controllerState.agents.findIndex((agent) => agent.id === agentId);
    if (index < 0) {
      throw new PocketError(409, "agent_unavailable", "That Codex agent is no longer available.");
    }
    const agent = this.#controllerState.agents[index];
    await this.#perform(() => this.#desktop.focusAgent(agent.id), `Focused ${agent.label}.`);
    this.#markFocusedAgent(agent.id);
  }

  #markFocusedAgent(agentId) {
    this.#controllerState.focusedAgentId = agentId;
    this.#controllerState.focusedAgentIndex = this.#controllerState.agents.findIndex((agent) => agent.id === agentId);
    this.#controllerState.agents = this.#controllerState.agents.map((agent) => ({
      ...agent,
      focused: agent.id === agentId,
    }));
  }

  #recordAction(message) {
    this.#status = { ...this.#status, state: "ready", message };
    this.#session.state = this.#controllerState.taskState === "error" ? "error" : "active";
    this.#session.canInterrupt = this.#status.controls.stop;
    this.#session.terminalTail = message;
    this.#session.updatedAt = new Date().toISOString();
    this.#touch("snapshot_changed");
  }

  #publicSession() {
    return { ...this.#session };
  }

  #stateFingerprint() {
    return JSON.stringify([this.#status, this.#controllerState, this.#session.state, this.#session.canInterrupt]);
  }

  #trimIdempotencyCache() {
    while (this.#idempotency.size > MAX_IDEMPOTENCY_ENTRIES) {
      this.#idempotency.delete(this.#idempotency.keys().next().value);
    }
  }

  #touch(eventType, data = {}) {
    this.#revision += 1;
    this.#events.publish(eventType, { revision: `r_${this.#revision}`, ...data });
  }
}

function legacyAction(command) {
  switch (command.kind) {
    case "start":
      requireCommandKeys(command, ["kind"], ["workspaceId"]);
      if (command.workspaceId !== undefined && (typeof command.workspaceId !== "string" || command.workspaceId.length === 0)) {
        throw new ControllerProfileValidationError("workspaceId must be non-empty text when provided.");
      }
      return { type: "attach" };
    case "attach":
      requireCommandKeys(command, ["kind"]);
      return { type: "attach" };
    case "focus":
      requireCommandKeys(command, ["kind", "sessionId"]);
      if (command.sessionId !== DESKTOP_SESSION_ID) {
        throw new PocketError(404, "unknown_session", "Vibe Pocket is attached only to the current desktop Codex task.");
      }
      return { type: "attach" };
    case "voice":
      requireCommandKeys(command, ["kind"]);
      return { type: "voice" };
    case "stop":
    case "interrupt":
      requireCommandKeys(command, ["kind"]);
      return { type: "stop" };
    case "accept":
    case "approve":
      requireCommandKeys(command, ["kind"]);
      return { type: "approve" };
    case "reject":
      requireCommandKeys(command, ["kind"]);
      return { type: "reject" };
    case "new_task":
    case "new_chat":
      requireCommandKeys(command, ["kind"]);
      return { type: "new_task" };
    case "focus_next":
      requireCommandKeys(command, ["kind"]);
      return { type: "focus_next" };
    case "focus_agent":
      requireCommandKeys(command, ["kind", "index"]);
      return { type: "focus_agent", index: command.index };
    case "navigate":
      requireCommandKeys(command, ["kind", "direction"]);
      return { type: "navigate", direction: command.direction };
    case "reasoning_depth":
      requireCommandKeys(command, ["kind", "delta"]);
      return { type: "reasoning_depth", delta: command.delta };
    case "workflow":
      requireCommandKeys(command, ["kind", "workflowId"]);
      return { type: "workflow", workflowId: command.workflowId };
    default:
      throw new PocketError(400, "unsupported_command", "This Vibe Pocket controller action is not supported.");
  }
}

function requireCommandKeys(command, required, optional = []) {
  const allowed = new Set([...required, ...optional]);
  const actual = Object.keys(command);
  if (required.some((key) => !Object.hasOwn(command, key)) || actual.some((key) => !allowed.has(key))) {
    throw new ControllerProfileValidationError("Controller command contains missing or unsupported fields.");
  }
}

function emptyControls() {
  return {
    voice: false,
    stop: false,
    "new-task": false,
    approve: false,
    reject: false,
    "clear-input": false,
    "focus-agent": false,
    "mode-cycle": false,
    navigate: false,
    reasoning: false,
    workflow: false,
  };
}

function normalizeControls(value) {
  const defaults = emptyControls();
  if (!value || typeof value !== "object" || Array.isArray(value)) return defaults;
  return Object.fromEntries(Object.keys(defaults).map((key) => [key, value[key] === true]));
}

function emptyControllerState() {
  return {
    taskState: "idle",
    agents: [],
    focusedAgentIndex: -1,
    focusedAgentId: null,
    voice: { available: false, active: false },
    mode: { available: false, label: "" },
    reasoning: { available: false, label: "" },
  };
}

function normalizeControllerState(result, previousFocusedAgentId) {
  const agents = Array.isArray(result.agents)
    ? result.agents
      .filter((agent) => agent && typeof agent.id === "string" && /^agent-[a-f0-9]{24}$/.test(agent.id)
        && typeof agent.label === "string" && typeof agent.state === "string")
      .slice(0, 6)
      .map((agent) => ({
        id: agent.id,
        label: agent.label.slice(0, 64),
        state: TASK_STATES.has(agent.state) ? agent.state : "idle",
        focused: agent.focused === true,
      }))
    : [];
  const reportedFocused = agents.find((agent) => agent.focused)?.id ?? null;
  const focusedAgentId = reportedFocused
    ?? (agents.some((agent) => agent.id === previousFocusedAgentId) ? previousFocusedAgentId : null);
  const agentsWithFocus = agents.map((agent) => ({ ...agent, focused: agent.id === focusedAgentId }));
  return {
    taskState: TASK_STATES.has(result.taskState) ? result.taskState : "idle",
    agents: agentsWithFocus,
    focusedAgentIndex: focusedAgentId ? agentsWithFocus.findIndex((agent) => agent.id === focusedAgentId) : -1,
    focusedAgentId,
    voice: normalizeVoice(result.voice),
    mode: normalizeSelector(result.mode),
    reasoning: normalizeSelector(result.reasoning),
  };
}

function normalizeVoice(value) {
  return {
    available: value?.available === true,
    active: value?.active === true,
  };
}

function normalizeSelector(value) {
  return {
    available: value?.available === true,
    label: typeof value?.label === "string" ? value.label.slice(0, 80) : "",
  };
}
