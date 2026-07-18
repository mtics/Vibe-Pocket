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
  updateControllerLayerColor,
  updateControllerWorkflowPrompt,
  validateControllerAction,
  validateGesture,
  validateInputId,
  validateLayerId,
  workflowPrompt,
} from "./controller-profile.mjs";
import { PocketError } from "./pocket-error.mjs";

const DESKTOP_SESSION_ID = "vibe-pocket-codex";
const MAX_IDEMPOTENCY_ENTRIES = 256;
const ACTION_REFRESH_DEBOUNCE_MS = 160;
const TASK_STATES = new Set(["idle", "unread", "thinking", "executing", "waiting", "complete", "error"]);
const CODEX_HOOK_EVENTS = new Set(["UserPromptSubmit", "PreToolUse", "PermissionRequest", "PostToolUse", "Stop"]);

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
  #actionRefresh = null;
  #actionRefreshTimer = null;

  constructor({
    workspaces,
    events,
    desktop,
    profile,
    profileStore = null,
    pollIntervalMs = 1_000,
  }) {
    super();
    if (!desktop) {
      throw new TypeError("DesktopCodexService requires a visible Codex desktop controller.");
    }
    this.#workspaces = workspaces;
    this.#events = events;
    this.#desktop = desktop;
    this.#profile = profile ? normalizeControllerProfile(profile) : createDefaultControllerProfile();
    this.#profileStore = profileStore;
    this.#activeLayerId = this.#profile.layers[0].id;
    this.#pollIntervalMs = pollIntervalMs;
    this.#session = {
      id: DESKTOP_SESSION_ID,
      workspaceId: "Vibe Pocket Codex",
      state: "starting",
      terminalTail: "Checking the visible Codex desktop controls...",
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
    await this.#actionRefresh?.catch(() => {});
    this.#actionRefresh = null;
    if (this.#actionRefreshTimer) clearTimeout(this.#actionRefreshTimer);
    this.#actionRefreshTimer = null;
    await this.#desktop.dispose?.();
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

  async bindDesktopThread(threadId) {
    const execution = this.#commandQueue.then(async () => {
      await this.#perform(
        () => this.#desktop.bindThread(threadId),
        "Attached the current Codex desktop task.",
      );
      return { attached: true, revision: `r_${this.#revision}` };
    });
    this.#commandQueue = execution.catch(() => {});
    return execution;
  }

  async codexHook(event, payload) {
    if (!CODEX_HOOK_EVENTS.has(event)) {
      throw new PocketError(400, "invalid_hook_event", "Unsupported Codex lifecycle event.");
    }
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      throw new PocketError(400, "invalid_hook_payload", "Codex lifecycle payload must be a JSON object.");
    }

    const lifecycle = await this.#desktop.applyLifecycleHook(event, payload);
    await this.#refreshAvailability({ publishIfChanged: true });
    const response = await lifecycle.response;
    await this.#refreshAvailability({ publishIfChanged: true });
    return response;
  }

  async command(command, idempotencyKey) {
    if (!idempotencyKey || idempotencyKey.length > 160) {
      throw new PocketError(400, "idempotency_key_required", "Every controller action needs an Idempotency-Key header.");
    }

    const fingerprint = commandFingerprint(command);
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

      if (command.kind === "update_layer_color") {
        requireCommandKeys(command, ["kind", "layerId", "color"]);
        const next = updateControllerLayerColor(this.#profile, {
          layerId: command.layerId,
          color: command.color,
        });
        await this.#replaceProfile(next, `Updated ${command.layerId} color.`);
        return;
      }

      if (command.kind === "update_workflow") {
        requireCommandKeys(command, ["kind", "workflowId", "prompt"]);
        const next = updateControllerWorkflowPrompt(this.#profile, {
          workflowId: command.workflowId,
          prompt: command.prompt,
        });
        await this.#replaceProfile(next, `Updated ${command.workflowId} workflow.`);
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
      const message = error.message || "Codex did not accept this controller action.";
      this.#status = { ...this.#status, message };
      this.#session.terminalTail = message;
      this.#session.updatedAt = new Date().toISOString();
      this.#touch("snapshot_changed");
      this.#scheduleActionRefresh();
      throw new PocketError(409, "desktop_action_failed", message);
    }
  }

  async #executeAction(action) {
    action = validateControllerAction(action);
    switch (action.type) {
      case "attach":
        await this.#perform(() => this.#desktop.attach(), "Resumed the focused Vibe Pocket Codex task.");
        return;
      case "voice":
        await this.#setVoice(!this.#controllerState.voice.active);
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
          throw new PocketError(400, "invalid_direction", "Navigation direction must be up, down, left, or right.");
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
        const agents = this.#controllerState.agents;
        if (agents.length === 0) {
          await this.#perform(() => this.#desktop.attach(), "Resumed the focused Vibe Pocket Codex task.");
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
      case "select_layer": {
        const layer = this.#profile.layers.find(({ id }) => id === action.layerId);
        if (!layer) throw new PocketError(409, "layer_unavailable", "That controller layer is unavailable.");
        this.#activeLayerId = layer.id;
        this.#recordAction(`Selected ${layer.name}.`);
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
          () => this.#desktop.workflow(workflowPrompt(this.#profile, action.workflowId)),
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
      this.#controllerState = normalizeControllerState(result);
      this.#session.state = this.#controllerState.taskState === "error" ? "error" : "active";
      this.#session.canInterrupt = this.#status.controls.stop;
      this.#session.terminalTail = result.message ?? "Ready to control Vibe Pocket Codex tasks.";
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
    this.#recordAction(result?.message ?? fallbackMessage);
    this.#scheduleActionRefresh();
  }

  #scheduleActionRefresh() {
    if (this.#actionRefreshTimer) clearTimeout(this.#actionRefreshTimer);
    this.#actionRefreshTimer = setTimeout(() => {
      this.#actionRefreshTimer = null;
      if (this.#actionRefresh) return;
      this.#actionRefresh = this.#refreshAvailability({ publishIfChanged: true })
        .finally(() => { this.#actionRefresh = null; });
    }, ACTION_REFRESH_DEBOUNCE_MS);
    this.#actionRefreshTimer.unref?.();
  }

  async #press(control, fallbackMessage) {
    await this.#perform(() => this.#desktop.press(control), fallbackMessage);
  }

  async #setVoice(active) {
    if (active && !this.#controllerState.voice.available) {
      throw new PocketError(409, "voice_unavailable", "The visible ChatGPT Codex dictation control is unavailable.");
    }
    const result = await this.#desktop.setVoice(active);
    this.#controllerState.voice = { ...this.#controllerState.voice, active };
    this.#recordAction(result?.message ?? (active ? "Started ChatGPT Codex dictation." : "Stopped ChatGPT Codex dictation."));
    this.#scheduleActionRefresh();
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

function commandFingerprint(command) {
  return JSON.stringify(command);
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
    "access-cycle": false,
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
    access: { available: false, label: "" },
    reasoning: { available: false, label: "" },
    userInput: null,
  };
}

function normalizeControllerState(result) {
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
  const focusedAgentId = reportedFocused;
  const agentsWithFocus = agents.map((agent) => ({ ...agent, focused: agent.id === focusedAgentId }));
  return {
    taskState: TASK_STATES.has(result.taskState) ? result.taskState : "idle",
    agents: agentsWithFocus,
    focusedAgentIndex: focusedAgentId ? agentsWithFocus.findIndex((agent) => agent.id === focusedAgentId) : -1,
    focusedAgentId,
    voice: normalizeVoice(result.voice),
    mode: normalizeSelector(result.mode),
    access: normalizeSelector(result.access),
    reasoning: normalizeSelector(result.reasoning),
    userInput: normalizeUserInput(result.userInput),
  };
}

function normalizeUserInput(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const questionCount = Number.isInteger(value.questionCount) ? value.questionCount : 0;
  const questionIndex = Number.isInteger(value.questionIndex) ? value.questionIndex : -1;
  if (questionCount < 1 || questionCount > 3 || questionIndex < 0 || questionIndex >= questionCount) return null;
  if (typeof value.header !== "string" || typeof value.question !== "string" || !Array.isArray(value.options)) return null;
  const options = value.options.slice(0, 8).map((option) => ({
    label: typeof option?.label === "string" ? option.label.slice(0, 120) : "",
    description: typeof option?.description === "string" ? option.description.slice(0, 500) : "",
  })).filter(({ label }) => label.length > 0);
  const selectedOptionIndex = options.length > 0 && Number.isInteger(value.selectedOptionIndex)
    ? Math.max(0, Math.min(options.length - 1, value.selectedOptionIndex))
    : -1;
  return {
    questionIndex,
    questionCount,
    header: value.header.slice(0, 64),
    question: value.question.slice(0, 2_000),
    options,
    selectedOptionIndex,
    hasSpokenAnswer: value.hasSpokenAnswer === true,
    isSecret: value.isSecret === true,
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
